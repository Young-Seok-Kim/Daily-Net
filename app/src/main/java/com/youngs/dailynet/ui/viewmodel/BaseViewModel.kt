package com.youngs.dailynet.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

open class BaseViewModel : ViewModel() {
    // 💡 Compose UI에서 관찰할 토스트 메시지 상태
    var toastMessage by mutableStateOf<String?>(null)
        protected set // 상속받은 ViewModel 클래스들만 값을 변경할 수 있도록 제한

    // 💡 토스트를 출력한 후 상태를 비워주는 공통 함수
    fun onToastShown() {
        toastMessage = null
    }

    // 💡 자식 ViewModel들이 편하게 토스트를 구울 수 있도록 헬퍼 함수 제공
    protected fun showToast(message: String) {
        toastMessage = message
    }
}