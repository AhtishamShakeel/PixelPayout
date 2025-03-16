package com.example.pixelpayout.utils

import kotlinx.coroutines.flow.Flow

interface ConnectivityCheck {
    val isConnected: Flow<Boolean>
}