package com.autoclicker

import java.util.UUID

/**
 * 一个自动点击场景：
 * - matchText：屏幕上出现的关键文字（不区分大小写，包含即命中）
 * - action：
 *     "node" -> 直接点击「包含该文字的控件节点」
 *     "coord" -> 命中文字后，点击指定坐标 (clickX, clickY)
 */
data class Scenario(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val matchText: String = "",
    val action: String = "node", // "node" | "coord"
    val clickX: Int = 0,
    val clickY: Int = 0,
    val enabled: Boolean = true
)
