package com.createbyte.lootlevel.utils

import kotlinx.coroutines.flow.Flow

interface ConnectivityCheck {
    val isConnected: Flow<Boolean>
}