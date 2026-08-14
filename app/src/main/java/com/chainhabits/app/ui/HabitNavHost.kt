package com.chainhabits.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chainhabits.app.HabitApplication
import com.chainhabits.app.ui.detail.DetailScreen
import com.chainhabits.app.ui.detail.DetailViewModel
import com.chainhabits.app.ui.edit.EditScreen
import com.chainhabits.app.ui.edit.EditViewModel
import com.chainhabits.app.ui.home.HomeScreen

private const val NEW_HABIT = -1L

@Composable
fun HabitNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onAddHabit = { nav.navigate("edit/$NEW_HABIT") },
                onOpenHabit = { id -> nav.navigate("habit/$id") },
            )
        }

        composable(
            route = "habit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            DetailScreen(
                viewModel = viewModel(factory = detailFactory(id)),
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("edit/$id") },
            )
        }

        composable(
            route = "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: NEW_HABIT
            EditScreen(
                viewModel = viewModel(factory = editFactory(id)),
                onDone = { nav.popBackStack() },
            )
        }
    }
}

private fun detailFactory(habitId: Long): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app =
                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as HabitApplication
            DetailViewModel(app.repository, habitId)
        }
    }

private fun editFactory(habitId: Long): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app =
                this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as HabitApplication
            EditViewModel(app.repository, habitId)
        }
    }
