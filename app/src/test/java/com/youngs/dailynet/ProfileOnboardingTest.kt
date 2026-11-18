package com.youngs.dailynet

import com.youngs.dailynet.ui.view.isProfileInputValid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가입 1단계(신체 정보)의 '다음' 활성 조건.
 *
 * 1단계는 필수라 닫을 수 없으므로, 이 조건이 틀리면 사용자가 온보딩에 갇히거나(너무 엄격)
 * 빈 값이 서버에 저장된다(너무 느슨). 2단계(AI 분석 참고 정보)는 선택이라 조건이 없다.
 */
class ProfileOnboardingTest {

    @Test
    fun completeInputPasses() {
        assertTrue(isProfileInputValid("175", "85", "1997-01-24"))
        // 소수점 키·체중도 받는다 (DnFormSheet의 Decimal 키보드)
        assertTrue(isProfileInputValid("175.5", "85.3", "2000-12-31"))
    }

    @Test
    fun missingOrZeroValuesFail() {
        assertFalse(isProfileInputValid("", "85", "1997-01-24"))
        assertFalse(isProfileInputValid("175", "", "1997-01-24"))
        // 0은 toFloatOrNull이 0f를 돌려주므로 길이 검사만으로는 통과한다
        assertFalse(isProfileInputValid("0", "85", "1997-01-24"))
        assertFalse(isProfileInputValid("175", "0", "1997-01-24"))
        // 숫자가 아닌 글자도 0으로 떨어진다
        assertFalse(isProfileInputValid("키", "85", "1997-01-24"))
    }

    @Test
    fun birthDateMustBePicked() {
        // 날짜를 아직 고르지 않으면 ""이다 (null이 아니라 빈 문자열로 온다)
        assertFalse(isProfileInputValid("175", "85", ""))
        // DnDatePickerDialog는 yyyy-MM-dd로 준다
        assertTrue(isProfileInputValid("175", "85", "1997-01-24"))
    }
}
