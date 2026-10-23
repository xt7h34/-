package com.rigen.volumeui

/* Generated together with the wbg_* drawables so the colours in code and in XML always match. */

class StyleDef(
    val nameRes: Int,
    val start: Int,
    val end: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val bgRes: Int,
)

object WidgetPalettes {
    /** Styles 1 to 7. Style 0 is each widget's own look and style 8 is one plain colour. */
    val STYLES: List<StyleDef> = listOf(
        StyleDef(R.string.widget_style_1, 0xFF2E1A8F.toInt(), 0xFF8E44F0.toInt(), 0xFFFFFFFF.toInt(), 0xB8FFFFFF.toInt(), 0xFFFFD76A.toInt(), R.drawable.wbg_s1),
        StyleDef(R.string.widget_style_2, 0xFFFF7A18.toInt(), 0xFFD4145A.toInt(), 0xFFFFFFFF.toInt(), 0xC8FFFFFF.toInt(), 0xFFFFF3C4.toInt(), R.drawable.wbg_s2),
        StyleDef(R.string.widget_style_3, 0xFF0B6E99.toInt(), 0xFF0F9D9A.toInt(), 0xFFFFFFFF.toInt(), 0xC8FFFFFF.toInt(), 0xFFFFE9A0.toInt(), R.drawable.wbg_s3),
        StyleDef(R.string.widget_style_4, 0xFF0F5132.toInt(), 0xFF2E9E6B.toInt(), 0xFFFFFFFF.toInt(), 0xC8FFFFFF.toInt(), 0xFFD9FF9E.toInt(), R.drawable.wbg_s4),
        StyleDef(R.string.widget_style_5, 0xFF7B1E5A.toInt(), 0xFFD6336C.toInt(), 0xFFFFFFFF.toInt(), 0xC8FFFFFF.toInt(), 0xFFFFD3E6.toInt(), R.drawable.wbg_s5),
        StyleDef(R.string.widget_style_6, 0xFF0F1226.toInt(), 0xFF2A2F5C.toInt(), 0xFFFFFFFF.toInt(), 0xB8FFFFFF.toInt(), 0xFF8F9BFF.toInt(), R.drawable.wbg_s6),
        StyleDef(R.string.widget_style_7, 0xFFFFF8EC.toInt(), 0xFFF2E6D0.toInt(), 0xFF2B2433.toInt(), 0xFF6B6275.toInt(), 0xFFC2410C.toInt(), R.drawable.wbg_s7),
    )

    val SIGNATURE: Map<WidgetKind, StyleDef> = mapOf(
        WidgetKind.ALARM to StyleDef(R.string.widget_style_0, 0xFF1F2A44.toInt(), 0xFFE07A5F.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFE8B0.toInt(), R.drawable.wbg_sig_alarm),
        WidgetKind.PRAYER to StyleDef(R.string.widget_style_0, 0xFF053B3A.toInt(), 0xFF118C7A.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFBFF5E6.toInt(), R.drawable.wbg_sig_prayer),
        WidgetKind.REWARDS to StyleDef(R.string.widget_style_0, 0xFF3B1470.toInt(), 0xFFC2358F.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFE08A.toInt(), R.drawable.wbg_sig_rewards),
        WidgetKind.SLEEP to StyleDef(R.string.widget_style_0, 0xFF0A0F2E.toInt(), 0xFF3B3F9E.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFA9B3FF.toInt(), R.drawable.wbg_sig_sleep),
        WidgetKind.HABITS to StyleDef(R.string.widget_style_0, 0xFF0E3B2B.toInt(), 0xFF2FA36B.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFD3FF9E.toInt(), R.drawable.wbg_sig_habits),
        WidgetKind.REELS to StyleDef(R.string.widget_style_0, 0xFF5A1560.toInt(), 0xFFFF6B3D.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFE3A3.toInt(), R.drawable.wbg_sig_reels),
        WidgetKind.GOALS to StyleDef(R.string.widget_style_0, 0xFF9A3412.toInt(), 0xFFF59E0B.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0xFFFFF4CC.toInt(), R.drawable.wbg_sig_goals),
    )

    val SOLIDS: List<Int> = listOf(
        0xFF1F2937.toInt(),
        0xFF0F172A.toInt(),
        0xFF7C3AED.toInt(),
        0xFFDB2777.toInt(),
        0xFFDC2626.toInt(),
        0xFFEA580C.toInt(),
        0xFFCA8A04.toInt(),
        0xFF16A34A.toInt(),
        0xFF0891B2.toInt(),
        0xFF2563EB.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF111111.toInt(),
    )
}
