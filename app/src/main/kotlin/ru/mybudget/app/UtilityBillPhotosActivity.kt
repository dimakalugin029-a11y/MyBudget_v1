package ru.mybudget.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mybudget.app.data.UtilityBillEntity
import ru.mybudget.app.data.UtilityBillPhotoEntity
import ru.mybudget.app.utilities.UtilityPhotoPreferences
import ru.mybudget.app.utilities.UtilityPhotoStorage
import ru.mybudget.app.utilities.UtilityUserTemplate

class UtilityBillPhotosActivity : AppCompatActivity() {
    private lateinit var manager: BudgetManager
    private lateinit var adapter: PhotosAdapter
    private var billId: Int = 0
    private var bill: UtilityBillEntity? = null
    private var pendingPhotoType: String = UtilityBillPhotoEntity.TYPE_RECEIPT

    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
    ) { uris ->
        if (uris.isNotEmpty()) persistPhotos(uris, pendingPhotoType)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_utility_bill_photos)
        billId = intent.getIntExtra(UtilitiesActivity.EXTRA_BILL_ID, 0)
        if (billId == 0) {
            finish()
            return
        }
        manager = BudgetManager.getInstance(this)
        ScreenHeaderHelper.setup(
            this,
            getString(R.string.utility_photos_screen_title),
            getString(R.string.main_icon_utilities),
        )
        adapter = PhotosAdapter(
            onOpen = { openPhoto(it) },
            onLongClick = { confirmDeletePhoto(it) },
        )
        findViewById<RecyclerView>(R.id.billPhotosRecyclerView).apply {
            layoutManager = GridLayoutManager(this@UtilityBillPhotosActivity, 3)
            adapter = this@UtilityBillPhotosActivity.adapter
        }
        findViewById<MaterialButton>(R.id.addPhotoButton).setOnClickListener { showAddPhotoDialog() }
        loadPhotos()
    }

    override fun onResume() {
        super.onResume()
        if (billId != 0 && bill != null) {
            loadPhotos()
        }
    }

    private fun dao() = manager.utilityDao

    private fun loadPhotos() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val billEntity = dao().getBillById(billId) ?: return@withContext null
                val photos = dao().getPhotosForBill(billId)
                billEntity to photos
            } ?: run {
                Toast.makeText(this@UtilityBillPhotosActivity, R.string.utility_bill_load_error, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val (billEntity, photos) = loaded
            bill = billEntity
            bindHeader(billEntity, photos.size)
            adapter.submit(photos)
            findViewById<View>(R.id.billPhotosEmpty).visibility =
                if (photos.isEmpty()) View.VISIBLE else View.GONE
            findViewById<RecyclerView>(R.id.billPhotosRecyclerView).visibility =
                if (photos.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun bindHeader(billEntity: UtilityBillEntity, count: Int) {
        findViewById<TextView>(R.id.billPhotosPeriod).text =
            UtilityUserTemplate.titlePeriod(billEntity.year, billEntity.month) +
                " · " + getString(R.string.utility_photos_title, count)
        val folderHint = findViewById<TextView>(R.id.billPhotosFolderHint)
        if (UtilityPhotoPreferences.hasFolder(this)) {
            folderHint.visibility = View.VISIBLE
            folderHint.text = getString(
                R.string.utility_photo_folder_bill_hint,
                UtilityPhotoStorage.monthFolderLabel(billEntity.year, billEntity.month),
            )
        } else {
            folderHint.visibility = View.GONE
        }
    }

    private fun showAddPhotoDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.utility_photo_pick_type_title)
            .setItems(
                arrayOf(
                    getString(R.string.utility_photo_type_receipt_full),
                    getString(R.string.utility_photo_type_meter_full),
                ),
            ) { _, which ->
                pendingPhotoType = if (which == 1) {
                    UtilityBillPhotoEntity.TYPE_METER
                } else {
                    UtilityBillPhotoEntity.TYPE_RECEIPT
                }
                pickPhotosLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
            .show()
    }

    private fun openPhoto(photo: UtilityBillPhotoEntity) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(photo.storedUri), "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.utility_receipt_photo_view, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeletePhoto(photo: UtilityBillPhotoEntity) {
        AlertDialog.Builder(this)
            .setMessage(R.string.utility_photo_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> deletePhoto(photo) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePhoto(photo: UtilityBillPhotoEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            UtilityPhotoStorage.deleteStoredPhoto(this@UtilityBillPhotosActivity, photo.storedUri)
            dao().deleteBillPhotoById(photo.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@UtilityBillPhotosActivity, R.string.utility_photo_removed, Toast.LENGTH_SHORT).show()
                loadPhotos()
            }
        }
    }

    private fun persistPhotos(uris: List<Uri>, photoType: String) {
        val billEntity = bill ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var sortOrder = dao().getMaxPhotoSortOrder(billEntity.id)
            var saved = 0
            for (uri in uris) {
                sortOrder += 1
                val storedUri = UtilityPhotoStorage.persistPhoto(
                    this@UtilityBillPhotosActivity,
                    uri,
                    billEntity,
                    photoType,
                    sortOrder,
                ) ?: continue
                dao().insertBillPhoto(
                    UtilityBillPhotoEntity(
                        billId = billEntity.id,
                        photoType = photoType,
                        storedUri = storedUri,
                        sortOrder = sortOrder,
                    ),
                )
                saved += 1
            }
            withContext(Dispatchers.Main) {
                if (saved > 0) {
                    Toast.makeText(
                        this@UtilityBillPhotosActivity,
                        getString(R.string.utility_photo_saved_count, saved),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                loadPhotos()
            }
        }
    }

    private class PhotosAdapter(
        private val onOpen: (UtilityBillPhotoEntity) -> Unit,
        private val onLongClick: (UtilityBillPhotoEntity) -> Unit,
    ) : RecyclerView.Adapter<PhotosAdapter.PhotoHolder>() {
        private var photos: List<UtilityBillPhotoEntity> = emptyList()

        fun submit(list: List<UtilityBillPhotoEntity>) {
            photos = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_utility_bill_photo, parent, false)
            return PhotoHolder(view)
        }

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            val photo = photos[position]
            bindPhotoThumb(holder.thumb, photo.storedUri)
            val badgeRes = if (photo.photoType == UtilityBillPhotoEntity.TYPE_METER) {
                R.string.utility_photo_type_meter
            } else {
                R.string.utility_photo_type_receipt
            }
            holder.badge.setText(badgeRes)
            holder.itemView.setOnClickListener { onOpen(photo) }
            holder.itemView.setOnLongClickListener {
                onLongClick(photo)
                true
            }
        }

        override fun getItemCount(): Int = photos.size

        class PhotoHolder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.photoThumb)
            val badge: TextView = v.findViewById(R.id.photoTypeBadge)
        }

        companion object {
            fun bindPhotoThumb(imageView: ImageView, storedUri: String) {
                imageView.setImageDrawable(null)
                if (storedUri.isBlank()) return
                val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return
                runCatching { imageView.setImageURI(uri) }.onFailure {
                    imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
        }
    }
}
