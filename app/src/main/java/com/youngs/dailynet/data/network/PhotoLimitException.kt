package com.youngs.dailynet.data.network

/**
 * 오늘 사진 인식 한도를 다 썼을 때(HTTP 429) 던진다.
 *
 * "음식을 못 알아봤다"와 반드시 구분해야 한다. 전자는 다시 찍으면 되지만
 * 이건 다시 찍어도 안 되고, 무료 사용자에게는 구독을 안내해야 할 자리이기 때문이다.
 */
class PhotoLimitException : Exception("Daily photo scan limit reached")
