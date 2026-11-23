package com.example

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.FleetRepository
import com.example.data.local.SentinelDatabase
import com.example.security.AndroidKeystoreCipher
import com.example.ssh.SshSessionManager
import com.example.ui.viewmodel.FleetViewModel
import com.example.ui.viewmodel.WorkspaceViewModel

class SentinelContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database by lazy { SentinelDatabase.getInstance(appContext) }
    private val cipher by lazy { AndroidKeystoreCipher() }

    val fleetRepository: FleetRepository by lazy {
        FleetRepository(
            serverDao = database.serverProfileDao(),
            snippetDao = database.commandSnippetDao(),
            cipher = cipher
        )
    }

    fun fleetViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FleetViewModel(fleetRepository, appContext.contentResolver) as T
        }
    }

    fun workspaceViewModelFactory(serverId: Long): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkspaceViewModel(
                    serverId = serverId,
                    repository = fleetRepository,
                    sshManager = SshSessionManager()
                ) as T
            }
        }
}
