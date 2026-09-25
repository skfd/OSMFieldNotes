package com.surveyme.presentation.notes

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.surveyme.R
import com.surveyme.data.NoteActionQueue
import com.surveyme.data.OsmAuthManager
import com.surveyme.data.OsmAuthState
import com.surveyme.databinding.DialogCreateNoteBinding
import java.util.Locale

/**
 * Compose a new OSM note at a fixed point (item 7). Opened from the map's create FAB, a map
 * long-press and the Home button, all through [show]. Shows the coordinates and who the note
 * will be posted as; Post stays disabled while the text is blank and after it was tapped.
 * Logged out, Post first asks "Post anonymously?" (the API allows it) with a way to the login
 * in Settings instead. The note itself goes through [NoteActionQueue.enqueueCreate], so it
 * appears at once as a pending placeholder and survives being offline.
 */
class CreateNoteDialogFragment : DialogFragment() {

    private var _binding: DialogCreateNoteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val lat = requireArguments().getDouble(ARG_LAT)
        val lon = requireArguments().getDouble(ARG_LON)
        _binding = DialogCreateNoteBinding.inflate(layoutInflater)

        val me = OsmAuthManager.get(requireContext()).state.value as? OsmAuthState.LoggedIn
        binding.textWhere.text = getString(R.string.create_note_where, lat, lon)
        binding.textAs.text = if (me != null) getString(R.string.create_note_as, me.displayName)
        else getString(R.string.create_note_anonymously)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_note_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            // Listener set after show() so a tap does not close the dialog by itself.
            .setPositiveButton(R.string.create_note_post, null)
            .create()

        dialog.setOnShowListener {
            val post = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            post.isEnabled = !binding.inputText.text.isNullOrBlank()
            binding.inputText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    post.isEnabled = !s.isNullOrBlank()
                }
            })
            post.setOnClickListener {
                val text = binding.inputText.text?.toString().orEmpty().trim()
                if (text.isEmpty()) return@setOnClickListener
                if (OsmAuthManager.get(requireContext()).isLoggedIn) {
                    post.isEnabled = false
                    submit(lat, lon, text)
                } else {
                    confirmAnonymous { post.isEnabled = false; submit(lat, lon, text) }
                }
            }
            binding.inputText.requestFocus()
        }
        return dialog
    }

    private fun confirmAnonymous(onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_note_anonymous_title)
            .setMessage(R.string.create_note_anonymous_message)
            .setPositiveButton(R.string.create_note_anonymous_confirm) { _, _ -> onConfirmed() }
            .setNeutralButton(R.string.create_note_log_in) { _, _ ->
                dismiss()
                activity?.findViewById<BottomNavigationView>(R.id.bottomNavigation)
                    ?.selectedItemId = R.id.settingsFragment
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun submit(lat: Double, lon: Double, text: String) {
        NoteActionQueue.get(requireContext()).enqueueCreate(lat, lon, text)
        Toast.makeText(context, R.string.create_note_queued, Toast.LENGTH_SHORT).show()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CreateNoteDialogFragment"
        private const val ARG_LAT = "lat"
        private const val ARG_LON = "lon"

        fun show(manager: FragmentManager, lat: Double, lon: Double) {
            if (manager.findFragmentByTag(TAG) != null) return
            CreateNoteDialogFragment().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_LAT, lat)
                    putDouble(ARG_LON, lon)
                }
            }.show(manager, TAG)
        }

        /** "43.65123, -79.38345" for confirmations. */
        fun formatPoint(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }
}
