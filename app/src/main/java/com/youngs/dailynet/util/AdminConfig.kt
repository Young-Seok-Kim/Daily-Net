package com.youngs.dailynet.util

object AdminConfig {
    // 본인의 이메일을 여기에 넣으세요.
    private val adminEmails = listOf("seok9562@gmail.com", "sjyy9797@gmail.com")

    fun isUserAdmin(email: String?): Boolean {
        return email != null && adminEmails.contains(email)
    }
}