package com.zynt.mangaautoscroller.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zynt.mangaautoscroller.util.FileManagerUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val _mangaImages = MutableStateFlow<List<Uri>>(emptyList())
    val mangaImages: StateFlow<List<Uri>> = _mangaImages

    private val _isScrolling = MutableStateFlow(false)
    val isScrolling: StateFlow<Boolean> = _isScrolling

    private val _scrollSpeed = MutableStateFlow(2000L)
    val scrollSpeed: StateFlow<Long> = _scrollSpeed

    fun loadMangaFromDirectory(directoryUri: Uri) {
        viewModelScope.launch {
            val images = FileManagerUtil.getImageFilesFromDirectory(getApplication(), directoryUri)
            _mangaImages.value = images
        }
    }

    fun toggleScrolling() {
        _isScrolling.value = !_isScrolling.value
    }

    fun updateScrollSpeed(newSpeed: Long) {
        _scrollSpeed.value = newSpeed
    }
}