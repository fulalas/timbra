package com.timbra.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.timbra.BuildConfig
import com.timbra.R
import com.timbra.data.model.Category
import com.timbra.data.model.CategoryKind
import com.timbra.databinding.FragmentListBinding
import com.timbra.ui.linearWithDivider
import com.timbra.ui.list.TrackListFragment

class LibraryFragment : Fragment(), MenuProvider {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        val categories = listOf(
            Category(CategoryKind.FOLDERS, R.drawable.matte_folders, R.string.cat_folders),
            Category(CategoryKind.ALBUMS, R.drawable.matte_albums, R.string.cat_albums),
            Category(CategoryKind.ARTISTS, R.drawable.matte_artists, R.string.cat_artists),
            Category(CategoryKind.SONGS, R.drawable.matte_all_songs, R.string.cat_songs),
            Category(CategoryKind.GENRES, R.drawable.matte_genres, R.string.cat_genres),
            Category(CategoryKind.PLAYLISTS, R.drawable.matte_playlists, R.string.cat_playlists),
            Category(CategoryKind.QUEUE, R.drawable.matte_queue, R.string.cat_queue),
        )
        b.recycler.linearWithDivider()
        b.recycler.adapter = CategoryAdapter(categories) { onCategory(it) }
    }

    private fun onCategory(cat: Category) {
        val nav = findNavController()
        when (cat.kind) {
            CategoryKind.FOLDERS -> nav.navigate(
                R.id.folderTreeFragment,
                bundleOf("folderPath" to "", "folderTitle" to getString(R.string.cat_folders)),
            )
            CategoryKind.QUEUE -> nav.navigate(R.id.queueFragment)
            else -> {
                val kind = when (cat.kind) {
                    CategoryKind.ALBUMS -> TrackListFragment.KIND_ALBUMS
                    CategoryKind.ARTISTS -> TrackListFragment.KIND_ARTISTS
                    CategoryKind.GENRES -> TrackListFragment.KIND_GENRES
                    CategoryKind.PLAYLISTS -> TrackListFragment.KIND_PLAYLISTS
                    else -> TrackListFragment.KIND_SONGS
                }
                nav.navigate(
                    R.id.trackListFragment,
                    bundleOf(
                        "listKind" to kind,
                        "listTitle" to getString(cat.titleRes),
                    ),
                )
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_library, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_about -> { showAbout(); true }
        else -> false
    }

    private fun showAbout() {
        val body = getString(
            R.string.about_body,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID,
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
