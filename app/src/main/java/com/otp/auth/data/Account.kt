package com.otp.auth.data

data class Account(
    val id: String = java.util.UUID.randomUUID().toString(),
    val issuer: String,
    val label: String,
    val secret: String
)