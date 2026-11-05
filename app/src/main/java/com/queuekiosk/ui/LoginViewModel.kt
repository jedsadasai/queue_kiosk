package com.queuekiosk.ui

import android.app.Application
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.queuekiosk.R
import com.queuekiosk.data.ApiException
import com.queuekiosk.kiosk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class LoginForm(
    val url: String,
    val username: String,
    val password: String,
    val openHour: Int,
    val closeHour: Int,
    val showHistorySheet: Boolean,
    val showTotalQueue: Boolean,
    val showQrCode: Boolean,
)

class LoginViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(val loading: Boolean = false, val status: String = "", val isError: Boolean = false)

    private val kiosk = app.kiosk
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _loggedIn = Channel<Unit>(Channel.CONFLATED)
    val loggedIn = _loggedIn.receiveAsFlow()

    fun login(form: LoginForm) {
        if (_state.value.loading) return
        validate(form)?.let { error(it); return }

        _state.value = UiState(loading = true, status = str(R.string.login_signing_in))
        viewModelScope.launch {
            try {
                kiosk.auth.login(form.url.trim(), form.username.trim(), form.password)
                kiosk.prefs.apply {
                    openHour = form.openHour
                    closeHour = form.closeHour
                    showHistorySheet = form.showHistorySheet
                    showTotalQueue = form.showTotalQueue
                    showQrCode = form.showQrCode
                }
                _state.value = UiState(status = str(R.string.login_success))
                _loggedIn.send(Unit)
            } catch (e: ApiException) {
                _state.value = UiState(status = e.userMessage(getApplication<Application>()), isError = true)
            }
        }
    }

    /** Same checks as fleQ LoginActivity.validateTextFields(). */
    @StringRes
    private fun validate(f: LoginForm): Int? {
        val url = f.url.trim()
        return when {
            url.isEmpty() || f.username.isBlank() || f.password.isEmpty() -> R.string.login_fill_all_fields
            !(url.startsWith("http://") || url.startsWith("https://")) -> R.string.login_url_needs_scheme
            !Patterns.WEB_URL.matcher(url).matches() -> R.string.error_invalid_url
            f.openHour >= f.closeHour -> R.string.login_invalid_hours
            else -> null
        }
    }

    private fun error(@StringRes res: Int) {
        _state.value = UiState(status = str(res), isError = true)
    }

    private fun str(@StringRes res: Int): String = getApplication<Application>().getString(res)
}
