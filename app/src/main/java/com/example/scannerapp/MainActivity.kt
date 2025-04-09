package com.example.scannerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var viewAttendanceButton: Button
    private lateinit var resultText: TextView
    private lateinit var db: SQLiteDatabase

    private val CAMERA_PERMISSION_CODE = 101
    private val DB_NAME = "attendees.db"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 👈 FIRST: set layout

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar) // 👈 THEN: set it as the support action bar

        scanButton = findViewById(R.id.scanButton)
        viewAttendanceButton = findViewById(R.id.viewAttendanceButton)
        resultText = findViewById(R.id.resultText)
        scanButton.isEnabled = false
        viewAttendanceButton.isEnabled = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            onCameraPermissionGranted()
        }
    }


    private fun onCameraPermissionGranted() {
        Toast.makeText(this, "Camera permission granted!", Toast.LENGTH_SHORT).show()
        scanButton.isEnabled = true
        viewAttendanceButton.isEnabled = true

        val dbHelper = DatabaseHelper(this)
        dbHelper.copyDatabaseIfNeeded()

        val dbPath = getDatabasePath(DB_NAME).path
        db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)

        scanButton.setOnClickListener {
            val integrator = IntentIntegrator(this)
            integrator.setPrompt("Scan Roll Number QR Code")
            integrator.setOrientationLocked(true)
            integrator.setBeepEnabled(true)
            integrator.initiateScan()
        }

        viewAttendanceButton.setOnClickListener {
            showAttendanceRecords()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result.contents != null) {
            val rollno = result.contents
            val cursor = db.rawQuery("SELECT marked FROM Attendees WHERE rollno = ?", arrayOf(rollno))
            if (cursor.moveToFirst()) {
                val marked = cursor.getInt(0)
                if (marked == 1) {
                    resultText.text = "Roll No: $rollno already marked!"
                    Toast.makeText(this, "Attendance already marked for $rollno", Toast.LENGTH_LONG).show()
                } else {
                    db.execSQL("UPDATE Attendees SET marked = 1 WHERE rollno = ?", arrayOf(rollno))
                    resultText.text = "Roll No: $rollno marked successfully!"
                    Toast.makeText(this, "Attendance marked for $rollno", Toast.LENGTH_LONG).show()
                }
            } else {
                resultText.text = "Roll No: $rollno not found!"
                Toast.makeText(this, "Roll number not found in database!", Toast.LENGTH_LONG).show()
            }
            cursor.close()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun showAttendanceRecords() {
        val cursor = db.rawQuery("SELECT rollno, marked FROM Attendees", null)

        if (cursor.count == 0) {
            Toast.makeText(this, "No records found!", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = StringBuilder()
        while (cursor.moveToNext()) {
            val rollno = cursor.getString(0)
            val marked = cursor.getInt(1)
            val status = if (marked == 1) "✔ Marked" else "❌ Not Marked"
            builder.append("Roll No: $rollno → $status\n")
        }
        cursor.close()

        AlertDialog.Builder(this)
            .setTitle("Attendance Records")
            .setMessage(builder.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraPermissionGranted()
        } else {
            Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ MENU SECTION

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> {
                Toast.makeText(this, "Search clicked", Toast.LENGTH_SHORT).show()
                showSearchDialog()
                return true
            }
            R.id.menu_export -> {
                Toast.makeText(this, "Export clicked", Toast.LENGTH_SHORT).show()
                exportToCSV()
                return true
            }
            R.id.menu_reset -> {
                Toast.makeText(this, "Reset clicked", Toast.LENGTH_SHORT).show()
                resetAttendance()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }


    private fun showSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Search Roll Number")

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        builder.setPositiveButton("Search") { dialog, _ ->
            val rollno = input.text.toString()
            val cursor = db.rawQuery("SELECT marked FROM Attendees WHERE rollno = ?", arrayOf(rollno))
            if (cursor.moveToFirst()) {
                val marked = cursor.getInt(0)
                val status = if (marked == 1) "✔ Attendance marked" else "❌ Not marked"
                Toast.makeText(this, "Roll No: $rollno → $status", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Roll No: $rollno not found", Toast.LENGTH_LONG).show()
            }
            cursor.close()
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun exportToCSV() {
        try {
            val fileName = "attendance_export.csv"
            val file = File(getExternalFilesDir(null), fileName)
            val writer = FileWriter(file)

            val cursor = db.rawQuery("SELECT rollno, marked FROM Attendees", null)
            writer.append("RollNo,Marked\n")

            while (cursor.moveToNext()) {
                val rollno = cursor.getString(0)
                val marked = cursor.getInt(1)
                writer.append("$rollno,$marked\n")
            }

            cursor.close()
            writer.flush()
            writer.close()

            Toast.makeText(this, "Exported to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Export failed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetAttendance() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Reset Attendance")
        builder.setMessage("Are you sure you want to reset all attendance?")
        builder.setPositiveButton("Yes") { _, _ ->
            db.execSQL("UPDATE Attendees SET marked = 0")
            Toast.makeText(this, "Attendance reset successfully", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}
