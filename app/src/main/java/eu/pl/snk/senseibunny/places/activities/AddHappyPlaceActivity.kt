package eu.pl.snk.senseibunny.places.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import com.google.android.gms.location.LocationRequest
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import eu.pl.snk.senseibunny.places.R
import eu.pl.snk.senseibunny.places.database.PlaceApp
import eu.pl.snk.senseibunny.places.database.PlaceDao
import eu.pl.snk.senseibunny.places.database.PlaceEntity
import eu.pl.snk.senseibunny.places.databinding.ActivityAddHappyPlaceBinding
import eu.pl.snk.senseibunny.places.models.PlaceModel
import eu.pl.snk.senseibunny.places.utils.GetAdressFromLatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.*

class AddHappyPlaceActivity : AppCompatActivity() {

    private var binding: ActivityAddHappyPlaceBinding ?=null

    private var tvSelectedDate: TextView? = null

    private var buttonAnim: Button ?=null

    private var animation: Animation?= null

    public var saveImage : String?=   null

    public var mLatitude: Double = 0.0

    public var mLongitude: Double = 0.0

    private lateinit var LocationProvider: FusedLocationProviderClient

    private val startAutocomplete : ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult())
        { result ->
            if(result.resultCode == RESULT_OK  && result.data !=null) {
                val intent: Intent = result.data!!
                val place = Autocomplete.getPlaceFromIntent(intent)
                binding?.location?.setText(place.address)
                mLatitude=place.latLng!!.latitude
                mLongitude=place.latLng!!.longitude

            } else if (result.resultCode == RESULT_CANCELED) {
                // The user canceled the operation, will work on on back Pressed
                Toast.makeText(this@AddHappyPlaceActivity,"User canceled",Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddHappyPlaceBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        setSupportActionBar(binding?.toolBar)

        if(supportActionBar!=null){
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        binding?.toolBar?.setNavigationOnClickListener{
            onBackPressedDispatcher.onBackPressed()

        }

        binding?.date?.setOnClickListener{
            clickDatePicker()
        }

        binding?.uploadImage?.setOnClickListener{
            val pictureDialog = AlertDialog.Builder(this)
            pictureDialog.setTitle("Select Action")
            val pictureDialogItems = arrayOf("Choose from Gallery", "Take a Picture")
            pictureDialog.setItems(pictureDialogItems) { dialog, which ->
                when(which){
                    0->chooseFromGallery()
                    1-> takeApic()
                }
            }
            pictureDialog.show()
        }

        buttonAnim=binding?.breathButton

        animation= AnimationUtils.loadAnimation(this, R.anim.breath)
        buttonAnim?.startAnimation(animation)

        val placeDao = (application as PlaceApp).db.placesDao()




        if(intent.hasExtra("place")){
            supportActionBar?.title="Edit Place"

            val place = intent.getSerializableExtra("place") as PlaceModel;
            binding?.title?.setText(place.title)
            binding?.desc?.setText(place.description)
            binding?.date?.setText(place.date)
            binding?.location?.setText(place.location)
            binding?.imagePlaceholder?.setImageURI(Uri.parse(place.image))

            val id = place.id

            val requestCode=intent.getIntExtra("requestCode",0) as Int

            if(requestCode==0){
                binding?.breathButton?.setText("Update Place")
                binding?.breathButton?.setOnClickListener{
                    updateRecord(placeDao,id)
                }
            }


        }else{
            binding?.breathButton?.setText("Insert Place")
            binding?.breathButton?.setOnClickListener{
                saveRecord(placeDao)
            }
        }


        if(!Places.isInitialized()){
            Places.initialize(this, resources.getString(R.string.PlacesApiKey))
        }

        binding?.location?.setOnClickListener{
            try{
                giveLocation()
            }
            catch(e: Exception){
                Log.e("Error",e.message.toString())
            }
        }

        LocationProvider=LocationServices.getFusedLocationProviderClient(this)

        binding?.locationButton?.setOnClickListener{
            if(isLocationEnabled().equals(false)){ //if location is disabled go to sett
                Toast.makeText(this,"Location is not enabled",Toast.LENGTH_LONG).show()

                val Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(Intent)
            }else{ //else we want to get permissions
                Dexter.withActivity(this).withPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                ).withListener(object: MultiplePermissionsListener{
                    override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                        if(report.areAllPermissionsGranted()){
                            Toast.makeText(this@AddHappyPlaceActivity,"Location Enabled",Toast.LENGTH_LONG  ).show()
                            requestNewLocationData()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        p0: MutableList<PermissionRequest>?,
                        p1: PermissionToken?
                    ) {
                        showRationaleDialogForPermissions()
                    }
                }).onSameThread().check()
            }
        }

    }

    @SuppressLint("MissingPermission")
    private fun requestNewLocationData() {
        val mLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()

        LocationProvider.requestLocationUpdates(
            mLocationRequest,
            mLocationCallback,
            Looper.myLooper()
        )
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val mLastLocation = locationResult.lastLocation
            mLatitude=mLastLocation!!.latitude
            mLongitude=mLastLocation!!.longitude

            val adressTask = GetAdressFromLatLng(this@AddHappyPlaceActivity, mLatitude, mLongitude)
            adressTask.setCustomAddressListener(object: GetAdressFromLatLng.AdressListener{
                override fun onAddressFound(address: String) {
                    binding?.location?.setText(address)
                }

                override fun onError()  {
                    Toast.makeText(this@AddHappyPlaceActivity,"Adress not found",Toast.LENGTH_LONG).show()
                }
            })

            lifecycleScope.launch(Dispatchers.IO){
                //CoroutineScope tied to this LifecycleOwner's Lifecycle.
                //This scope will be cancelled when the Lifecycle is destroyed
                adressTask.launchBackgroundProcessForRequest()  //starts the task to get the address in text from the lat and lng values
            }
        }
    }

    private fun isLocationEnabled(): Boolean { //asking if location is enabled
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager // getting location manager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    private fun giveLocation() {
        try {
            // These are the list of fields which we required is passed
            val fields = listOf(
                Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG,
                Place.Field.ADDRESS
            )
            // Start the autocomplete intent with a unique request code.
            val intent =
                Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, fields)
                    .build(this@AddHappyPlaceActivity)
            startAutocomplete.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun updateRecord(placeDao: PlaceDao, id:Int){
        val title = binding?.title?.text.toString()
        val description = binding?.desc?.text.toString()
        val date = binding?.date?.text.toString()
        val location = binding?.location?.text.toString()

        if(title.isNotEmpty()){
            lifecycleScope.launch{
                placeDao.update(PlaceEntity(id=id,title=title, description = description, date = date, location = location, image = saveImage.toString(),longitude=mLongitude, latitude = mLatitude))
            }
        }
        else{
            Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
        }

    }

    private fun saveRecord(placeDao: PlaceDao){
        val title = binding?.title?.text.toString()
        val description = binding?.desc?.text.toString()
        val date = binding?.date?.text.toString()
        val location = binding?.location?.text.toString()

        if(title.isNotEmpty()){
            lifecycleScope.launch{
            placeDao.insert(PlaceEntity(title=title, description = description, date = date, location = location, image = saveImage.toString(), longitude=mLongitude, latitude = mLatitude))
            }
        }
        else{
            Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
        }

    }

    private fun chooseFromGallery(){
        Dexter.withContext(this).withPermissions(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).withListener(object: MultiplePermissionsListener {

                override fun onPermissionsChecked(report: MultiplePermissionsReport) {if(report.areAllPermissionsGranted()){
                    val pickInent= Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    GalleryLaucnher.launch(pickInent)
                }

                }
                override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>, token: PermissionToken) {
                    showRationaleDialogForPermissions()
                    token?.cancelPermissionRequest()
                }
            }).onSameThread().check();

    }

    private val GalleryLaucnher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ result->
            if(result.resultCode== RESULT_OK && result.data!=null){

                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, Uri.parse(result.data?.data?.toString()))
                saveImage = saveImageToInternalStorage(bitmap).toString()
                Log.e("Image", "Path :: $saveImage")

                binding?.imagePlaceholder?.setImageURI(result.data?.data) // setting background of our app
            }
        }

    private fun takeApic(){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)!= PackageManager.PERMISSION_GRANTED){
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            startActivityForResult(intent, CAMERA)
        }
        else{
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), CAMERA)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if(requestCode == CAMERA){
            if(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivityForResult(intent, CAMERA)
            }
            else{
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(resultCode== Activity.RESULT_OK && requestCode == CAMERA){

            val thumbnail: Bitmap = data?.extras?.get("data") as Bitmap
            saveImage = saveImageToInternalStorage(thumbnail).toString()

            Log.e("Image", "Path :: $saveImage")
            binding?.imagePlaceholder?.setImageBitmap(thumbnail)
        }
    }

    private fun showRationaleDialogForPermissions(){
        AlertDialog.Builder(this).setMessage("It looks like you didnt give permissions thats sad L").setPositiveButton("Go to Settings") { dialog, which ->
            try{
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            } catch (e:Exception){
                e.printStackTrace()
            }
        }.setNegativeButton("Cancel") { dialog, which ->
            dialog.dismiss()
        }.show()

    }

    private fun clickDatePicker() {

        val myCalendar = Calendar.getInstance()
        val year = myCalendar.get(Calendar.YEAR)
        val month = myCalendar.get(Calendar.MONTH)
        val day = myCalendar.get(Calendar.DAY_OF_MONTH)

        val dpd = DatePickerDialog(
            this,
            DatePickerDialog.OnDateSetListener { view, selectedYear, selectedMonth, selectedDayOfMonth ->

                val selectedDate = "$selectedDayOfMonth/${selectedMonth + 1}/$selectedYear"
                Toast.makeText(this, selectedDate.toString(), Toast.LENGTH_LONG).show()
                binding?.date?.setText(selectedDate)
            },
            year,
            month,
            day
        )
        dpd.datePicker.maxDate= System.currentTimeMillis()-86400000 // setting max date to yesterday
        dpd.show()

    }

    private fun saveImageToInternalStorage(bitmap: Bitmap):Uri {
        val wrapper=ContextWrapper(applicationContext)
        var file = wrapper.getDir(IMAGE_DIRECTORY, Context.MODE_PRIVATE) //file accessible only from application
        file = File(file,"${UUID.randomUUID()}.jpg")// store image and add random id to it


        try{
            val stream: OutputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            stream.flush()
            stream.close()
        }catch (e:IOException){
            e.printStackTrace()
        }

        return Uri.parse(file.absolutePath)
    }

    companion object {
        private const val GALLERY=1
        private const val CAMERA=2
        private const val IMAGE_DIRECTORY="PlacesImages"
    }

}