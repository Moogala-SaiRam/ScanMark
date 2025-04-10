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
import androidx.core.content.FileProvider

import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var viewAttendanceButton: Button
    private lateinit var resultText: TextView
    private lateinit var db: SQLiteDatabase
    private val rollStatusMap = mutableMapOf<String, Boolean>()

    private val CAMERA_PERMISSION_CODE = 101
    private val IMPORT_CSV_REQUEST_CODE = 200
    private val DB_NAME = "attendees.db"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

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

        preloadRollStatusMap()

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

    private fun preloadRollStatusMap() {
        val cursor = db.rawQuery("SELECT rollno, marked FROM Attendees", null)
        while (cursor.moveToNext()) {
            val rollno = cursor.getString(0).uppercase()
            val marked = cursor.getInt(1) == 1
            rollStatusMap[rollno] = marked
        }
        cursor.close()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == IMPORT_CSV_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val reader = inputStream?.bufferedReader()
                    reader?.readLine() // Skip header
                    var inserted = 0

                    reader?.forEachLine { line ->
                        val rollno = line.trim().uppercase()
                        if (rollno.isNotEmpty()) {
                            db.execSQL(
                                "INSERT OR IGNORE INTO Attendees (rollno, marked) VALUES (?, 0)",
                                arrayOf(rollno)
                            )
                            if (!rollStatusMap.containsKey(rollno)) {
                                rollStatusMap[rollno] = false
                            }
                            inserted++
                        }
                    }

                    reader?.close()
                    inputStream?.close()

                    Toast.makeText(this, "✅ Imported $inserted students from CSV", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "❌ Failed to import CSV", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            if (result.contents != null) {
                val rollno = result.contents.trim().uppercase()
                val status = rollStatusMap[rollno]

                if (status == null) {
                    resultText.text = "Roll No: $rollno not found!"
                    Toast.makeText(this, "Roll number not found in database!", Toast.LENGTH_LONG).show()
                } else if (status) {
                    resultText.text = "Roll No: $rollno already marked!"
                    Toast.makeText(this, "Attendance already marked for $rollno", Toast.LENGTH_LONG).show()
                } else {
                    db.execSQL("UPDATE Attendees SET marked = 1 WHERE rollno = ?", arrayOf(rollno))
                    rollStatusMap[rollno] = true
                    resultText.text = "Roll No: $rollno marked successfully!"
                    Toast.makeText(this, "Attendance marked for $rollno", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAttendanceRecords() {
        val builder = StringBuilder()
        if (rollStatusMap.isEmpty()) {
            Toast.makeText(this, "No records found!", Toast.LENGTH_SHORT).show()
            return
        }

        for ((rollno, marked) in rollStatusMap) {
            val status = if (marked) "✔ Marked" else "❌ Not Marked"
            builder.append("Roll No: $rollno → $status\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Attendance Records")
            .setMessage(builder.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> {
                showSearchDialog()
                return true
            }
            R.id.menu_export -> {
                exportToCSV()
                return true
            }
            R.id.menu_reset -> {
                resetAttendance()
                return true
            }
            R.id.menu_import -> {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "text/*"
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                startActivityForResult(Intent.createChooser(intent, "Select CSV File"), IMPORT_CSV_REQUEST_CODE)
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
            val rollno = input.text.toString().trim().uppercase()
            val status = rollStatusMap[rollno]
            if (status == null) {
                Toast.makeText(this, "Roll No: $rollno not found", Toast.LENGTH_LONG).show()
            } else {
                val result = if (status) "✔ Attendance marked" else "❌ Not marked"
                Toast.makeText(this, "Roll No: $rollno → $result", Toast.LENGTH_LONG).show()
            }
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun exportToCSV() {
        try {
            val fileName = "attendance_export.csv"
            val exportDir = File(getExternalFilesDir(null), "")
            if (!exportDir.exists()) exportDir.mkdirs()

            val file = File(exportDir, fileName)
            val writer = FileWriter(file)
            writer.append("RollNo,Marked\n")

            for ((rollno, marked) in rollStatusMap) {
                writer.append("$rollno,${if (marked) 1 else 0}\n")
            }

            writer.flush()
            writer.close()

            // Use FileProvider to get secure URI
            val fileUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Attendance Export")
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Start the chooser
            startActivity(Intent.createChooser(shareIntent, "Share CSV via"))

            Toast.makeText(this, "CSV Exported and ready to share!", Toast.LENGTH_LONG).show()
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
            for (key in rollStatusMap.keys) {
                rollStatusMap[key] = false
            }
            Toast.makeText(this, "Attendance reset successfully", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
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
}
