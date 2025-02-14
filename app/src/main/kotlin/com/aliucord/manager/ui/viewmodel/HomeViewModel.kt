package com.aliucord.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import com.aliucord.manager.BuildConfig
import com.aliucord.manager.R
import com.aliucord.manager.domain.manager.PreferencesManager
import com.aliucord.manager.domain.repository.GithubRepository
import com.aliucord.manager.installer.util.uninstallApk
import com.aliucord.manager.network.dto.Commit
import com.aliucord.manager.network.utils.fold
import com.aliucord.manager.ui.util.DiscordVersion
import com.aliucord.manager.util.getPackageVersion
import com.aliucord.manager.util.showToast
import kotlinx.coroutines.*

class HomeViewModel(
    private val application: Application,
    private val github: GithubRepository,
    val preferences: PreferencesManager
) : ViewModel() {
    var supportedVersion by mutableStateOf<DiscordVersion>(DiscordVersion.None)
        private set

    var installedVersion by mutableStateOf<DiscordVersion>(DiscordVersion.None)
        private set

    val commits = Pager(PagingConfig(pageSize = 30)) {
        object : PagingSource<Int, Commit>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Commit> {
                val page = params.key ?: 0

                return github.getCommits(page).fold(
                    success = { commits ->
                        val prevKey = if (page > 0) page - 1 else null
                        val nextKey = if (commits.isNotEmpty()) page + 1 else null

                        LoadResult.Page(
                            data = commits,
                            prevKey = prevKey,
                            nextKey = nextKey
                        )
                    },
                    fail = { LoadResult.Error(it) }
                )
            }

            override fun getRefreshKey(state: PagingState<Int, Commit>) = state.anchorPosition?.let {
                state.closestPageToPosition(it)?.prevKey?.plus(1) ?: state.closestPageToPosition(it)?.nextKey?.minus(1)
            }
        }
    }.flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _fetchInstalledVersion()
            _fetchSupportedVersion()
        }
    }

    private suspend fun _fetchInstalledVersion() {
        try {
            val (versionName, versionCode) = application.getPackageVersion(preferences.packageName)

            withContext(Dispatchers.Main) {
                installedVersion = DiscordVersion.Existing(
                    type = DiscordVersion.parseVersionType(versionCode),
                    name = versionName.split("-")[0].trim(),
                    code = versionCode,
                )
            }
        } catch (t: PackageManager.NameNotFoundException) {
            withContext(Dispatchers.Main) {
                installedVersion = DiscordVersion.None
            }
        } catch (t: Throwable) {
            Log.e(BuildConfig.TAG, Log.getStackTraceString(t))

            withContext(Dispatchers.Main) {
                installedVersion = DiscordVersion.Error
            }
        }
    }

    private suspend fun _fetchSupportedVersion() {
        val version = github.getDataJson()

        withContext(Dispatchers.Main) {
            version.fold(
                success = {
                    val versionCode = it.versionCode.toIntOrNull() ?: return@fold

                    supportedVersion = DiscordVersion.Existing(
                        type = DiscordVersion.parseVersionType(versionCode),
                        name = it.versionName.split("-")[0].trim(),
                        code = versionCode,
                    )
                },
                fail = {
                    Log.e(BuildConfig.TAG, Log.getStackTraceString(it))
                    supportedVersion = DiscordVersion.Error
                }
            )
        }
    }

    fun fetchInstalledVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            _fetchInstalledVersion()
        }
    }

    fun fetchSupportedVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            _fetchSupportedVersion()
        }
    }

    fun launchAliucord() {
        val launchIntent = application.packageManager
            .getLaunchIntentForPackage(preferences.packageName)

        if (launchIntent != null) {
            application.startActivity(launchIntent)
        } else {
            application.showToast(R.string.launch_aliucord_fail)
        }
    }

    fun uninstallAliucord() {
        application.uninstallApk(preferences.packageName)
    }
}
