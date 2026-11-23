package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.screens.FleetScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.SentinelVpsTheme
import com.example.ui.viewmodel.FleetViewModel
import com.example.ui.viewmodel.WorkspaceViewModel
import kotlinx.serialization.Serializable

@Serializable
data object FleetRoute

@Serializable
data class WorkspaceRoute(val serverId: Long)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val container = SentinelContainer(applicationContext)

        setContent {
            SentinelVpsTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = FleetRoute
                ) {
                    composable<FleetRoute> {
                        val fleetViewModel: FleetViewModel = viewModel(
                            factory = remember { container.fleetViewModelFactory() }
                        )
                        FleetScreen(
                            viewModel = fleetViewModel,
                            onConnectServer = { serverId ->
                                navController.navigate(WorkspaceRoute(serverId))
                            }
                        )
                    }
                    composable<WorkspaceRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<WorkspaceRoute>()
                        val workspaceViewModel: WorkspaceViewModel = viewModel(
                            key = "workspace_${route.serverId}",
                            factory = remember(route.serverId) {
                                container.workspaceViewModelFactory(route.serverId)
                            }
                        )
                        WorkspaceScreen(
                            viewModel = workspaceViewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
