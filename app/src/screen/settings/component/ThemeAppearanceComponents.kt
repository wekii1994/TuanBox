/*
 * This file is part of YumeBox.
 *
 * YumeBox is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c)  YumeYucca 2025 - Present
 *
 */

@file:Suppress("UnusedSymbol", "FunctionName")

package com.github.yumeyucca.yumebox.screen.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.presentation.component.AppActionBottomSheet
import com.github.yumeyucca.yumebox.presentation.component.AppBottomSheetConfirmAction
import com.github.yumeyucca.yumebox.presentation.component.OemTextField
import com.github.yumeyucca.yumebox.presentation.icon.Yume
import com.github.yumeyucca.yumebox.presentation.icon.yume.Check
import com.github.yumeyucca.yumebox.presentation.icon.yume.Palette
import com.github.yumeyucca.yumebox.presentation.theme.DEFAULT_THEME_SEED_ARGB
import com.github.yumeyucca.yumebox.presentation.theme.UiDp
import com.github.yumeyucca.yumebox.presentation.theme.colorFromArgb
import com.github.yumeyucca.yumebox.presentation.theme.colorToArgbLong
import com.github.yumeyucca.yumebox.core.locale.YumeTxt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class ClassicThemeColorPreset(
    val name: String,
    val argb: Long,
) {
    val hex: String
        get() = "#${(argb and 0x00FFFFFFL).toString(16).padStart(6, '0').uppercase()}"
}

val CLASSIC_THEME_PRESETS = listOf(
    ClassicThemeColorPreset("极光青", DEFAULT_THEME_SEED_ARGB),
    ClassicThemeColorPreset("蔚蓝海洋", 0xFF1976D2L),
    ClassicThemeColorPreset("晴朗天蓝", 0xFF0288D1L),
    ClassicThemeColorPreset("薄荷翠绿", 0xFF2E7D32L),
    ClassicThemeColorPreset("清爽青碧", 0xFF00897BL),
    ClassicThemeColorPreset("落日暖橙", 0xFFE65100L),
    ClassicThemeColorPreset("蜜柑琥珀", 0xFFF57C00L),
    ClassicThemeColorPreset("樱花粉", 0xFFD81B60L),
    ClassicThemeColorPreset("梦幻紫", 0xFF7B1FA2L),
    ClassicThemeColorPreset("深邃靛蓝", 0xFF303F9FL),
    ClassicThemeColorPreset("中国正红", 0xFFC62828L),
    ClassicThemeColorPreset("极简玄灰", 0xFF37474FL),
)

@Composable
internal fun ThemeColorPickerItem(
    themeSeedColorArgb: Long,
    onThemeSeedColorChange: (Long) -> Unit,
) {
    var showThemeColorPicker by remember { mutableStateOf(false) }

    BasicComponent(
        title = YumeTxt.AppSettings.Interface.ColorThemeTitle,
        summary =
            YumeTxt.AppSettings.Interface.ColorThemeCustomSummary.format(
                formatThemeSeedHex(themeSeedColorArgb)
            ),
        onClick = { showThemeColorPicker = true },
        endActions = {
            val previewColor =
                remember(themeSeedColorArgb) {
                    runCatching { colorFromArgb(themeSeedColorArgb) }.getOrDefault(Color.White)
                }
            Icon(
                Yume.Palette,
                tint = previewColor,
                contentDescription = null,
                modifier = Modifier.padding(end = UiDp.dp12),
            )
        },
    )

    ThemeColorPickerSheet(
        show = showThemeColorPicker,
        initialSeedColorArgb = themeSeedColorArgb,
        onDismissRequest = { showThemeColorPicker = false },
        onConfirm = { argb ->
            onThemeSeedColorChange(argb)
            showThemeColorPicker = false
        },
    )
}

@Composable
internal fun ThemeColorPickerSheet(
    show: Boolean,
    initialSeedColorArgb: Long,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var pickerColor by
        remember(show, initialSeedColorArgb) {
            mutableStateOf(
                runCatching { colorFromArgb(initialSeedColorArgb) }.getOrDefault(Color.White)
            )
        }
    var hexField by
        remember(show, initialSeedColorArgb) {
            val hex = formatThemeSeedHex(initialSeedColorArgb)
            mutableStateOf(TextFieldValue(hex, TextRange(hex.length)))
        }
    var showAdvancedPicker by remember(show) { mutableStateOf(false) }

    AppActionBottomSheet(
        show = show,
        title = YumeTxt.AppSettings.Interface.ColorThemePickerTitle,
        onDismissRequest = onDismissRequest,
        defaultWindowInsetsPadding = false,
        endAction = {
            AppBottomSheetConfirmAction(onClick = { onConfirm(colorToArgbLong(pickerColor)) })
        },
        content = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Preset Header and "恢复默认配色" button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "经典主题预设",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    TextButton(
                        text = "恢复默认配色",
                        onClick = {
                            val defaultColor = colorFromArgb(DEFAULT_THEME_SEED_ARGB)
                            pickerColor = defaultColor
                            val hex = formatThemeSeedHex(DEFAULT_THEME_SEED_ARGB)
                            hexField = TextFieldValue(hex, TextRange(hex.length))
                        },
                    )
                }

                // Swatch grid (4 columns)
                val rows = remember { CLASSIC_THEME_PRESETS.chunked(4) }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            rowPresets.forEach { preset ->
                                val presetColor = remember(preset.argb) { colorFromArgb(preset.argb) }
                                val isSelected = remember(pickerColor, preset.argb) {
                                    val currentArgb = colorToArgbLong(pickerColor)
                                    (currentArgb and 0x00FFFFFFL) == (preset.argb and 0x00FFFFFFL)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                pickerColor = presetColor
                                                val hex = formatThemeSeedHex(preset.argb)
                                                hexField = TextFieldValue(hex, TextRange(hex.length))
                                            },
                                        ),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(presetColor)
                                            .then(
                                                if (isSelected) {
                                                    Modifier.border(
                                                        width = 2.5.dp,
                                                        color = MiuixTheme.colorScheme.primary,
                                                        shape = CircleShape,
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Yume.Check,
                                                contentDescription = "Selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }

                                    Text(
                                        text = preset.name,
                                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // Advanced Customization Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedPicker = !showAdvancedPicker }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showAdvancedPicker) "收起自定义色盘" else "高级自定义 (色盘与十六进制代码)",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = if (showAdvancedPicker) "▲" else "▼",
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                if (showAdvancedPicker) {
                    ColorPicker(
                        color = pickerColor,
                        onColorChanged = { color ->
                            pickerColor = color
                            val hex = formatThemeSeedHex(colorToArgbLong(color))
                            hexField = TextFieldValue(hex, TextRange(hex.length))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OemTextField(
                        value = hexField,
                        onValueChange = { updated ->
                            val upper = updated.copy(text = updated.text.uppercase())
                            hexField = upper
                            parseThemeHexColorOrNull(upper.text)?.let { pickerColor = it }
                        },
                        label = YumeTxt.AppSettings.Interface.ColorThemeCodeLabel,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = UiDp.dp8),
                    )
                }
            }
        },
    )
}

private fun formatThemeSeedHex(argb: Long): String {
    val rgb = (argb and 0x00FFFFFFL).toString(16).uppercase().padStart(6, '0')
    return "#$rgb"
}

private fun parseThemeHexColorOrNull(input: String): Color? {
    val body = input.trim().uppercase(java.util.Locale.ROOT).removePrefix("#").removePrefix("0X")
    if (body.length != 6 || !body.all { it in '0'..'9' || it in 'A'..'F' }) return null
    val rgb = body.toLongOrNull(16) ?: return null
    return colorFromArgb(0xFF000000L or rgb)
}
