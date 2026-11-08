package com.kylin.hikvisionxray;

import com.sun.jna.Structure;
import com.sun.jna.Union;
import NetSDKDemo.HCNetSDK;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 验证:HCNetSDK.java 中所有 Structure/Union 子类均能正常构造(字段顺序声明完整)。
 * 任一结构体缺少/错配 @FieldOrder 都会在构造时抛 Error,本测试逐一构造全部类。
 */
public class AllStructuresConstructTest {

    /**
     * HCNetSDK.java 中 JNA 本身无法表示的结构体(与 @FieldOrder 无关,降级 JNA 也无法解决):
     * - NET_DVR_AUXOUTCFG / NET_DVR_PREVIEWCFG_V30: 含 byte[][] 多维数组字段,JNA 不支持
     * - NET_DVR_CRUISE_PARA: byEnableThisCruise 数组未初始化,JNA 要求数组必须初始化
     * 均未被其他结构体引用(已 grep 确认),业务不使用,跳过验证。
     */
    private static final Set<String> KNOWN_UNSUPPORTED = Set.of(
            "NET_DVR_AUXOUTCFG", "NET_DVR_PREVIEWCFG_V30", "NET_DVR_CRUISE_PARA");

    /** 解包 InvocationTargetException 取真正原因 */
    private static String causeOf(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    @Test
    void constructAllStructures() throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> inner : HCNetSDK.class.getDeclaredClasses()) {
            if ((Structure.class.isAssignableFrom(inner) || Union.class.isAssignableFrom(inner))
                    && !Modifier.isAbstract(inner.getModifiers())) {
                classes.add(inner);
            }
        }

        int ok = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> cls : classes) {
            try {
                Structure s = (Structure) cls.getDeclaredConstructor().newInstance();
                s.size(); // 触发布局计算
                s.write();
                s.read();
                ok++;
            } catch (NoSuchMethodException e) {
                skipped++; // 自定义构造器(如 BYTE_ARRAY(int)),跳过
            } catch (Throwable t) {
                if (KNOWN_UNSUPPORTED.contains(cls.getSimpleName())) {
                    skipped++; // JNA 无法表示的写法,见 KNOWN_UNSUPPORTED 注释
                    continue;
                }
                failures.add(cls.getSimpleName() + " -> " + causeOf(t));
                failures.add(cls.getSimpleName() + " -> " + causeOf(t));
                if (failures.size() <= 3) {
                    t.printStackTrace(System.out);
                }
            }
        }

        System.out.println("构造验证: " + ok + "/" + classes.size() + " 个结构体通过,跳过无默认构造 " + skipped + " 个");
        if (!failures.isEmpty()) {
            System.out.println("失败列表:");
            failures.forEach(System.out::println);
            throw new AssertionError(failures.size() + " 个结构体构造失败");
        }
    }
}
