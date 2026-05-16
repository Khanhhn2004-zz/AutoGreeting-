package com.example.carchatbot.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carchatbot.data.local.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn

    init {
        viewModelScope.launch {
            userPreferencesRepository.ensureDeviceProtectedStoreReady()
            userPreferencesRepository.isLoggedIn.collect { loggedIn ->
                _isLoggedIn.value = loggedIn
            }
        }
    }
}
