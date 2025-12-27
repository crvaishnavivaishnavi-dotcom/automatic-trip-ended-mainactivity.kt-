package com.example.automatictripended

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    private var destinationLat: Double? = null
    private var destinationLng: Double? = null

    private var tripStarted = false
    private var smsSent = false

    private val selectedNumbers = mutableStateListOf<String>() // Selected contacts

    companion object {
        var latitude by mutableStateOf(0.0)
        var longitude by mutableStateOf(0.0)
        var currentDistance by mutableStateOf(0f)
    }

    // Multi-contact picker using ACTION_PICK for Contacts
    private val contactPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val cursor = result.data?.data?.let { uri ->
                    contentResolver.query(
                        uri,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null,
                        null,
                        null
                    )
                }

                cursor?.use {
                    if (it.moveToFirst()) {
                        val number =
                            it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                                .replace(" ", "") // Remove spaces
                        if (!selectedNumbers.contains(number)) {
                            selectedNumbers.add(number)
                        }
                    }
                }
            }
        }
    private fun getDestinationName(lat: Double, lng: Double): String {
        return try {
            val geocoder = android.location.Geocoder(this)
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (addresses!!.isNotEmpty()) {
                val address = addresses!![0]
                address.getAddressLine(0) ?: "the destination"
            } else {
                "the destination"
            }
        } catch (e: Exception) {
            "the destination"
        }
    }


    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false

            if (locationGranted && smsGranted) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Location & SMS permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000
        ).setMinUpdateIntervalMillis(2000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    latitude = location.latitude
                    longitude = location.longitude
                    checkDestinationReached(location)
                }
            }
        }

        requestPermissions()

        setContent {
            MaterialTheme {
                TripUI()
            }
        }
    }

    @Composable
    fun TripUI() {
        var latInput by remember { mutableStateOf("") }
        var lngInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Trip Tracker", style = MaterialTheme.typography.headlineMedium)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = latInput,
                onValueChange = { latInput = it },
                label = { Text("Destination Latitude") }
            )

            OutlinedTextField(
                value = lngInput,
                onValueChange = { lngInput = it },
                label = { Text("Destination Longitude") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = {
                // Open contact picker
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                contactPicker.launch(intent)
            }) {
                Text("SELECT CONTACTS (${selectedNumbers.size})")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = {
                destinationLat = latInput.toDoubleOrNull()
                destinationLng = lngInput.toDoubleOrNull()

                if (destinationLat == null || destinationLng == null || selectedNumbers.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Set destination & select contacts",
                        Toast.LENGTH_LONG
                    ).show()
                    return@Button
                }

                tripStarted = true
                smsSent = false

                Toast.makeText(
                    this@MainActivity,
                    "Trip Started",
                    Toast.LENGTH_SHORT
                ).show()
            }) {
                Text("START TRIP")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Lat: $latitude")
            Text("Lng: $longitude")
            Text("Distance: ${"%.2f".format(currentDistance)} meters")

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedNumbers.isNotEmpty()) {
                Text("Selected Contacts:")
                selectedNumbers.forEach { number ->
                    Text(number)
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.SEND_SMS)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun distanceInMeters(current: Location): Float {
        val destination = Location("destination").apply {
            latitude = destinationLat!!
            longitude = destinationLng!!
        }
        return current.distanceTo(destination)
    }

    private fun checkDestinationReached(currentLocation: Location) {
        if (!tripStarted || smsSent) return
        if (destinationLat == null || destinationLng == null) return

        val distance = distanceInMeters(currentLocation)
        currentDistance = distance

        if (distance <= 200) {
            sendSmsToAll()
            smsSent = true
            tripStarted = false

            Toast.makeText(this, "Trip Ended", Toast.LENGTH_LONG).show()
            stopLocationUpdates()
        }
    }

    private fun sendSmsToAll() {
        if (destinationLat == null || destinationLng == null) return

        val destinationName = getDestinationName(destinationLat!!, destinationLng!!)
        val message = "I reached $destinationName"

        try {
            val smsManager = SmsManager.getDefault()
            selectedNumbers.forEach { number ->
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            Toast.makeText(this, "SMS sent to ${selectedNumbers.size} contacts", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "SMS failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }
}
