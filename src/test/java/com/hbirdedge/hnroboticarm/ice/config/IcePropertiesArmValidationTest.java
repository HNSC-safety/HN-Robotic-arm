package com.hbirdedge.hnroboticarm.ice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机械臂配置的启动期校验。
 *
 * <p><b>钉住的不变量</b>：默认臂不在清单里 → <b>启动就拒绝</b>，
 * 而不是等到消防现场按消音键时才发现。
 *
 * <p>为什么这条值得钉：默认臂不合法意味着<b>每一次</b>机械臂调用都会失败，
 * 而调用往往发生在消防现场。带着定时炸弹上线比启动失败危险得多。
 */
@DisplayName("机械臂配置校验")
class IcePropertiesArmValidationTest {

    /**
     * 造一份带机械臂清单的配置。
     *
     * @param defaultArm 默认臂名，可为 null
     * @return 配置
     */
    private static IceProperties propsWithArms(String defaultArm) {
        IceProperties p = new IceProperties();
        Map<String, IceProperties.Arm> arms = new LinkedHashMap<>();
        arms.put("arm-a", new IceProperties.Arm());
        arms.put("arm-b", new IceProperties.Arm());
        p.setArms(arms);
        p.setDefaultArm(defaultArm);
        return p;
    }

    /**
     * 默认臂在清单里 → 放行。
     */
    @Test
    @DisplayName("默认臂在清单里 → 通过")
    void defaultArmInListIsAccepted() {
        assertDoesNotThrow(() -> propsWithArms("arm-a").validateArmConfig());
    }

    /**
     * 默认臂不在清单里 → 拒绝启动。
     */
    @Test
    @DisplayName("默认臂不在清单里 → 拒绝启动")
    void defaultArmNotInListIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> propsWithArms("arm-z").validateArmConfig());
        assertTrue(e.getMessage().contains("arm-z"),
                "错误信息应点名是哪个默认臂，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("arm-a"),
                "错误信息应列出已配置的臂名，实际: " + e.getMessage());
    }

    /**
     * 没配默认臂 → 放行（单臂/多臂都行，由调用方显式传 arm）。
     */
    @Test
    @DisplayName("没配默认臂 → 通过")
    void blankDefaultArmIsAccepted() {
        assertDoesNotThrow(() -> propsWithArms(null).validateArmConfig());
        assertDoesNotThrow(() -> propsWithArms("").validateArmConfig());
        assertDoesNotThrow(() -> propsWithArms("   ").validateArmConfig());
    }

    /**
     * 没配清单（单臂场景）→ 放行。
     *
     * <p>单臂场景下臂名只是标签，没有清单可校验。
     */
    @Test
    @DisplayName("没配清单 → 通过")
    void noArmListIsAccepted() {
        IceProperties p = new IceProperties();
        p.setDefaultArm("arm-a");
        assertDoesNotThrow(p::validateArmConfig);
    }
}
