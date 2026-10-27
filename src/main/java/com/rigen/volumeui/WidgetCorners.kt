package com.rigen.volumeui

/* Generated: one background drawable per corner step, so a widget's corners can be chosen without code drawing the card. */
object WidgetCorners {
    /** Corner radii in dp, in the order of each row below. */
    val STEPS = intArrayOf(0, 12, 20, 28, 40, 52)
    const val DEFAULT_STEP = 3

    private val MAP: Map<Int, IntArray> = mapOf(
        R.drawable.wbg_s1 to intArrayOf(R.drawable.wbg_s1_r0, R.drawable.wbg_s1_r12, R.drawable.wbg_s1_r20, R.drawable.wbg_s1, R.drawable.wbg_s1_r40, R.drawable.wbg_s1_r52),
        R.drawable.wbg_s2 to intArrayOf(R.drawable.wbg_s2_r0, R.drawable.wbg_s2_r12, R.drawable.wbg_s2_r20, R.drawable.wbg_s2, R.drawable.wbg_s2_r40, R.drawable.wbg_s2_r52),
        R.drawable.wbg_s3 to intArrayOf(R.drawable.wbg_s3_r0, R.drawable.wbg_s3_r12, R.drawable.wbg_s3_r20, R.drawable.wbg_s3, R.drawable.wbg_s3_r40, R.drawable.wbg_s3_r52),
        R.drawable.wbg_s4 to intArrayOf(R.drawable.wbg_s4_r0, R.drawable.wbg_s4_r12, R.drawable.wbg_s4_r20, R.drawable.wbg_s4, R.drawable.wbg_s4_r40, R.drawable.wbg_s4_r52),
        R.drawable.wbg_s5 to intArrayOf(R.drawable.wbg_s5_r0, R.drawable.wbg_s5_r12, R.drawable.wbg_s5_r20, R.drawable.wbg_s5, R.drawable.wbg_s5_r40, R.drawable.wbg_s5_r52),
        R.drawable.wbg_s6 to intArrayOf(R.drawable.wbg_s6_r0, R.drawable.wbg_s6_r12, R.drawable.wbg_s6_r20, R.drawable.wbg_s6, R.drawable.wbg_s6_r40, R.drawable.wbg_s6_r52),
        R.drawable.wbg_s7 to intArrayOf(R.drawable.wbg_s7_r0, R.drawable.wbg_s7_r12, R.drawable.wbg_s7_r20, R.drawable.wbg_s7, R.drawable.wbg_s7_r40, R.drawable.wbg_s7_r52),
        R.drawable.wbg_sig_alarm to intArrayOf(R.drawable.wbg_sig_alarm_r0, R.drawable.wbg_sig_alarm_r12, R.drawable.wbg_sig_alarm_r20, R.drawable.wbg_sig_alarm, R.drawable.wbg_sig_alarm_r40, R.drawable.wbg_sig_alarm_r52),
        R.drawable.wbg_sig_goals to intArrayOf(R.drawable.wbg_sig_goals_r0, R.drawable.wbg_sig_goals_r12, R.drawable.wbg_sig_goals_r20, R.drawable.wbg_sig_goals, R.drawable.wbg_sig_goals_r40, R.drawable.wbg_sig_goals_r52),
        R.drawable.wbg_sig_habits to intArrayOf(R.drawable.wbg_sig_habits_r0, R.drawable.wbg_sig_habits_r12, R.drawable.wbg_sig_habits_r20, R.drawable.wbg_sig_habits, R.drawable.wbg_sig_habits_r40, R.drawable.wbg_sig_habits_r52),
        R.drawable.wbg_sig_prayer to intArrayOf(R.drawable.wbg_sig_prayer_r0, R.drawable.wbg_sig_prayer_r12, R.drawable.wbg_sig_prayer_r20, R.drawable.wbg_sig_prayer, R.drawable.wbg_sig_prayer_r40, R.drawable.wbg_sig_prayer_r52),
        R.drawable.wbg_sig_reels to intArrayOf(R.drawable.wbg_sig_reels_r0, R.drawable.wbg_sig_reels_r12, R.drawable.wbg_sig_reels_r20, R.drawable.wbg_sig_reels, R.drawable.wbg_sig_reels_r40, R.drawable.wbg_sig_reels_r52),
        R.drawable.wbg_sig_rewards to intArrayOf(R.drawable.wbg_sig_rewards_r0, R.drawable.wbg_sig_rewards_r12, R.drawable.wbg_sig_rewards_r20, R.drawable.wbg_sig_rewards, R.drawable.wbg_sig_rewards_r40, R.drawable.wbg_sig_rewards_r52),
        R.drawable.wbg_sig_sleep to intArrayOf(R.drawable.wbg_sig_sleep_r0, R.drawable.wbg_sig_sleep_r12, R.drawable.wbg_sig_sleep_r20, R.drawable.wbg_sig_sleep, R.drawable.wbg_sig_sleep_r40, R.drawable.wbg_sig_sleep_r52),
        R.drawable.wbg_solid to intArrayOf(R.drawable.wbg_solid_r0, R.drawable.wbg_solid_r12, R.drawable.wbg_solid_r20, R.drawable.wbg_solid, R.drawable.wbg_solid_r40, R.drawable.wbg_solid_r52),
    )

    fun res(base: Int, step: Int): Int = MAP[base]?.getOrNull(step.coerceIn(0, STEPS.size - 1)) ?: base
}
