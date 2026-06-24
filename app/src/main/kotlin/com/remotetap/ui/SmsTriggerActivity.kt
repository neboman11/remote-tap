package com.remotetap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotetap.R
import com.remotetap.databinding.ActivitySmsTriggerBinding
import com.remotetap.model.SmsContact
import com.remotetap.repository.PreferencesRepository

class SmsTriggerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsTriggerBinding
    private lateinit var prefs: PreferencesRepository
    private val contacts = mutableListOf<SmsContact>()

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { importContact(it) }
        }
    }

    private val readContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openContactPicker()
    }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateSmsPermissionUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsTriggerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesRepository(this)

        binding.etTriggerMessage.setText(prefs.smsTriggerMessage)
        contacts.addAll(prefs.smsContacts)
        refreshContactList()
        updateSmsPermissionUi()

        binding.btnGrantSmsPermission.setOnClickListener {
            smsPermissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        }

        binding.btnAddContact.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                openContactPicker()
            } else {
                readContactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }

        binding.btnSave.setOnClickListener {
            prefs.smsTriggerMessage = binding.etTriggerMessage.text.toString().trim()
            prefs.smsContacts = contacts.toList()
            finish()
        }
    }

    private fun updateSmsPermissionUi() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        binding.tvSmsPermissionWarning.visibility = if (granted) View.GONE else View.VISIBLE
        binding.btnGrantSmsPermission.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openContactPicker() {
        contactPickerLauncher.launch(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        )
    }

    private fun importContact(uri: Uri) {
        val cursor = contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return
        cursor.use {
            if (!it.moveToFirst()) return
            val name = it.getString(0) ?: ""
            val raw = it.getString(1) ?: return
            val number = PhoneNumberUtils.normalizeNumber(raw) ?: raw
            val contact = SmsContact(name, number)
            if (contacts.none { c -> c.number == contact.number }) {
                contacts.add(contact)
                refreshContactList()
            }
        }
    }

    private fun refreshContactList() {
        val ll = binding.llContacts
        ll.removeAllViews()

        if (contacts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No contacts added yet"
                setTextColor(ContextCompat.getColor(this@SmsTriggerActivity, android.R.color.darker_gray))
                setPadding(0, 0, 0, 32)
            }
            ll.addView(empty)
            return
        }

        contacts.forEachIndexed { index, contact ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_sms_contact, ll, false)
            row.findViewById<TextView>(R.id.tv_contact_name).text = contact.name
            row.findViewById<TextView>(R.id.tv_contact_number).text = contact.number
            row.findViewById<Button>(R.id.btn_remove_contact).setOnClickListener {
                contacts.removeAt(index)
                refreshContactList()
            }
            ll.addView(row)
        }
    }
}
