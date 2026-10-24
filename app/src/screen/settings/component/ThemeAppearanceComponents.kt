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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.yumeyucca.yumebox.data.model.ThemeMode
import com.github.yumeyucca.yumebox.presentation.component.AppActionBottomSheet
import com.github.yumeyucca.yumebox.presentation.component.AppBottomSheetCloseAction
import com.github.yumeyucca.yumebox.presentation.component.AppBottomSheetConfirmAction
import com.github.yumeyucca.yumebox.presentation.component.EnumSelector
import com.github.yumeyucca.yumebox.presentation.component.OemTextField
import com.github.yumeyucca.yumebox.presentation.icon.Yume
import com.github.yumeyucca.yumebox.presentation.icon.yume.Check
import com.github.yumeyucca.yumebox.presentation.icon.yume.Palette
import com.github.yumeyucca.yumebox.presentation.theme.DEFAULT_CUSTOM_THEME_SEED_ARGB
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
    val hex: String,
)

val CLASSIC_THEME_PRESETS = listOf(
    ClassicThemeColorPreset("极光青", DEFAULT_CUSTOM_THEME_SEED_ARGB, "#016888"),
    ClassicThemeColorPreset("蔚蓝海洋", 0xFF1976D2L, "#1976D2"),
    ClassicThemeColorPreset("晴朗天蓝", 0xFF0288D1L, "#0288D1"),
    ClassicThemeColorPreset("薄荷翠绿", 0xFF2E7D32L, "#2E7D32"),
    ClassicThemeColorPreset("清爽青碧", 0xFF00897BL, "#00897B"),
    ClassicThemeColorPreset("落日暖橙", 0xFFE65100L, "#E65100"),
    ClassicThemeColorPreset("蜜柑琥珀", 0xFFF57C00L, "#F57C00"),
    ClassicThemeColorPreset("樱花粉", 0xFFD81B60L, "#D81B60"),
    ClassicThemeColorPreset("梦幻紫", 0xFF7B1FA2L, "#7B1FA2"),
    ClassicThemeColorPreset("深邃靛蓝", 0xFF303F9FL, "#303F9F"),
    ClassicThemeColorPreset("中国正红", 0xFFC62828L, "#C62828"),
    ClassicThemeColorPreset("极简玄灰", 0xFF37474FL, "#37474F"),
)

@Composable
internal fun ThemeModeAndColorItems(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeSeedColorArgb: Long,
    onThemeSeedColorChange: (Long) -> Unit,
) {
    ThemeModeSelectorItem(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
    ThemeColorPickerItem(
        themeSeedColorArgb = themeSeedColorArgb,
        onThemeSeedColorChange = onThemeSeedColorChange,
    )
}

@Composable
internal fun ThemeModeSelectorItem(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    EnumSelector(
        title = YumeTxt.AppSettings.Interface.ThemeModeTitle,
        summary = YumeTxt.AppSettings.Interface.ThemeModeSummary,
        currentValue = themeMode,
        items =
            listOf(
                YumeTxt.AppSettings.Interface.ThemeModeSystem,
                YumeTxt.AppSettings.Interface.ThemeModeLight,
                YumeTxt.AppSettings.Interface.ThemeModeDark,
            ),
        values = ThemeMode.entries,
        onValueChange = onThemeModeChange,
    )
}

@Composable
internal fun ThemeColorPickerItem(
    themeSeedColorArgb: Long,
    onThemeSeedColorChange: (Long) -> Unit,
) {
    ThemeColorPickerItem(
        themeSeedColorArgb = themeSeedColorArgb,
        onThemeSeedColorChange = onThemeSeedColorChange,
        showBottomSheetInPlace = true,
    )
}

@Composable
internal fun ThemeColorPickerItem(
    themeSeedColorArgb: Long,
    onThemeSeedColorChange: (Long) -> Unit,
    showBottomSheetInPlace: Boolean,
    onOpenPickerRequest: (() -> Unit)? = null,
) {
    val showThemeColorPicker = remember { mutableStateOf(false) }

    BasicComponent(
        title = YumeTxt.AppSettings.Interface.ColorThemeTitle,
        summary =
            YumeTxt.AppSettings.Interface.ColorThemeCustomSummary.format(
                formatThemeSeedHex(themeSeedColorArgb)
            ),
        onClick = {
            if (showBottomSheetInPlace) {
                showThemeColorPicker.value = true
            } else {
                onOpenPickerRequest?.invoke()
            }
        },
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

    if (showBottomSheetInPlace) {
        ThemeColorPickerSheet(
            show = showThemeColorPicker.value,
            initialSeedColorArgb = themeSeedColorArgb,
            onDismissRequest = { showThemeColorPicker.value = false },
            onConfirm = { argb ->
                onThemeSeedColorChange(argb)
                showThemeColorPicker.value = false
            },
        )
    }
}

@Composable
internal fun ThemeColorPickerSheet(
    show: Boolean,
    initialSeedColorArgb: Long,
    onDismissRequest: () -> Unit,
    onConfirm: (Long) -> Unit,
    renderInRootScaffold: Boolean = true,
) {
    val pickerColor =
        remember(show, initialSeedColorArgb) {
            mutableStateOf(
                runCatching { colorFromArgb(initialSeedColorArgb) }.getOrDefault(Color.White)
            )
        }
    val hexField =
        remember(show, initialSeedColorArgb) {
            val hex = formatThemeSeedHex(initialSeedColorArgb)
            mutableStateOf(TextFieldValue(hex, TextRange(hex.length)))
        }
    var showAdvancedPicker by remember { mutableStateOf(false) }

    AppActionBottomSheet(
        show = show,
        modifier = Modifier,
        title = YumeTxt.AppSettings.Interface.ColorThemePickerTitle,
        onDismissRequest = onDismissRequest,
        enableNestedScroll = true,
        renderInRootScaffold = renderInRootScaffold,
        defaultWindowInsetsPadding = false,
        startAction = { AppBottomSheetCloseAction(onClick = onDismissRequest) },
        endAction = {
            AppBottomSheetConfirmAction(onClick = { onConfirm(colorToArgbLong(pickerColor.value)) })
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header Row: Section Title + Reset to Default Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "推荐经典配色",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        text = "恢复默认配色",
                        onClick = {
                            val defaultColor = colorFromArgb(DEFAULT_CUSTOM_THEME_SEED_ARGB)
                            pickerColor.value = defaultColor
                            val hex = formatThemeSeedHex(DEFAULT_CUSTOM_THEME_SEED_ARGB)
                            hexField.value = TextFieldValue(hex, TextRange(hex.length))
                        },
                    )
                }

                // Grid of Color Circles (4 columns)
                val currentHex = formatThemeSeedHex(colorToArgbLong(pickerColor.value))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CLASSIC_THEME_PRESETS.chunked(4).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.Top,
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = currentHex.equals(preset.hex, ignoreCase = true)
                                val swatchColor = colorFromArgb(preset.argb)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                pickerColor.value = swatchColor
                                                hexField.value = TextFieldValue(preset.hex, TextRange(preset.hex.length))
                                            },
                                        )
                                        .padding(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .then(
                                                if (isSelected) {
                                                    Modifier.border(
                                                        width = 2.5.dp,
                                                        color = MiuixTheme.colorScheme.onSurface,
                                                        shape = CircleShape,
                                                    )
                                                } else {
                                                    Modifier.border(
                                                        width = 1.dp,
                                                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                        shape = CircleShape,
                                                    )
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
                        color = pickerColor.value,
                        onColorChanged = { color ->
                            pickerColor.value = color
                            val hex = formatThemeSeedHex(colorToArgbLong(color))
                            hexField.value = TextFieldValue(hex, TextRange(hex.length))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OemTextField(
                        value = hexField.value,
                        onValueChange = { updated ->
                            val upper = updated.copy(text = updated.text.uppercase())
                            hexField.value = upper
                            parseThemeHexColorOrNull(upper.text)?.let { pickerColor.value = it }
                        },
                        label = YumeTxt.AppSettings.Interface.ColorThemeCodeLabel,
                        modifier = Modifier
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
    val body = input.removePrefix("#").removePrefix("0x").uppercase()
    if (body.length != 6) return null
    val rgb = body.toLongOrNull(16) ?: return null
    return colorFromArgb(0xFF000000L or rgb)
}
