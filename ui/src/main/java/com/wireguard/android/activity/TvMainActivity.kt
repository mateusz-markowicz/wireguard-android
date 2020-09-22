/*
 * Copyright © 2020 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.forEach
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableBoolean
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter
import com.wireguard.android.databinding.TvActivityBinding
import com.wireguard.android.databinding.TvTunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.android.util.TunnelImporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TvMainActivity : AppCompatActivity() {
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        lifecycleScope.launch {
            TunnelImporter.importTunnel(contentResolver, data) {
                Toast.makeText(this@TvMainActivity, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    private var pendingTunnel: ObservableTunnel? = null
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val tunnel = pendingTunnel
        if (tunnel != null)
            setTunnelStateWithPermissionsResult(tunnel)
        pendingTunnel = null
    }

    private fun setTunnelStateWithPermissionsResult(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                tunnel.setStateAsync(Tunnel.State.TOGGLE)
            } catch (e: Throwable) {
                val error = ErrorMessages[e]
                val message = getString(R.string.error_up, error)
                Toast.makeText(this@TvMainActivity, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
            }
            updateStats()
        }
    }

    private lateinit var binding: TvActivityBinding
    private val isDeleting = ObservableBoolean()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = TvActivityBinding.inflate(layoutInflater)
        lifecycleScope.launch {
            binding.tunnels = Application.getTunnelManager().getTunnels()
            if (binding.tunnels?.isEmpty() == true)
                binding.importButton.requestFocus()
            else
                binding.tunnelList.requestFocus()
        }
        binding.isDeleting = isDeleting
        binding.rowConfigurationHandler = object : ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler<TvTunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TvTunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.isDeleting = isDeleting
                binding.root.setOnClickListener {
                    lifecycleScope.launch {
                        if (isDeleting.get()) {
                            try {
                                item.deleteAsync()
                            } catch (e: Throwable) {
                                val error = ErrorMessages[e]
                                val message = getString(R.string.config_delete_error, error)
                                Toast.makeText(this@TvMainActivity, message, Toast.LENGTH_LONG).show()
                                Log.e(TAG, message, e)
                            }
                        } else {
                            if (Application.getBackend() is GoBackend) {
                                val intent = GoBackend.VpnService.prepare(binding.root.context)
                                if (intent != null) {
                                    pendingTunnel = item
                                    permissionActivityResultLauncher.launch(intent)
                                    return@launch
                                }
                            }
                            setTunnelStateWithPermissionsResult(item)
                        }
                    }
                }
            }
        }
        binding.importButton.setOnClickListener {
            try {
                tunnelFileImportResultLauncher.launch("*/*")
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@TvMainActivity, getString(R.string.tv_error), Toast.LENGTH_LONG).show()
            }
        }
        binding.deleteButton.setOnClickListener {
            isDeleting.set(!isDeleting.get())
        }
        binding.executePendingBindings()
        setContentView(binding.root)

        lifecycleScope.launch {
            while (true) {
                updateStats()
                delay(1000)
            }
        }
    }

    private suspend fun updateStats() {
        binding.tunnelList.forEach { viewItem ->
            val listItem = DataBindingUtil.findBinding<TvTunnelListItemBinding>(viewItem)
                    ?: return@forEach
            try {
                val tunnel = listItem.item!!
                if (tunnel.state != Tunnel.State.UP || isDeleting.get()) {
                    throw Exception()
                }
                val statistics = tunnel.getStatisticsAsync()
                val rx = statistics.totalRx()
                val tx = statistics.totalTx()
                listItem.tunnelTransfer.text = getString(R.string.transfer_rx_tx, QuantityFormatter.formatBytes(rx), QuantityFormatter.formatBytes(tx))
                listItem.tunnelTransfer.visibility = View.VISIBLE
            } catch (_: Throwable) {
                listItem.tunnelTransfer.visibility = View.GONE
                listItem.tunnelTransfer.text = ""
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/TvMainActivity"
    }
}