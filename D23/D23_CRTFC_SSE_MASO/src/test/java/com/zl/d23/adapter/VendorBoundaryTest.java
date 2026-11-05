package com.zl.d23.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应商边界守卫测试（Vendor Boundary Test）。
 *
 * <p>直接解析 {@code target/classes} 下每个 class 文件的常量池，断言 DeepSeek 供应商包
 * （内部名前缀 {@code org/springframework/ai/deepseek}）<b>只出现在
 * {@code com/zl/d23/adapter/deepseek/} 子包内</b>。任何业务/服务/控制器/观测类一旦
 * 直接引用供应商专有类型，该测试立即失败，从而把“供应商无关”从约定固化为可回归的机器校验。</p>
 *
 * <p>纯 JDK 实现，不依赖额外库；Javadoc 与普通字符串不会进入常量池类引用，故不产生误报。</p>
 */
class VendorBoundaryTest {

    private static final String VENDOR_PREFIX = "org/springframework/ai/deepseek";
    private static final String ALLOWED_DIR = "com/zl/d23/adapter/deepseek/";

    @Test
    @DisplayName("DeepSeek 类型引用物理上只存在于 adapter/deepseek 子包")
    void vendorTypesAreConfinedToAdapterPackage() throws Exception {
        // 以主类的 code source 定位 target/classes（surefire 下为目录而非 jar）
        URI classesUri = Class.forName("com.zl.d23.D23SpringAiApplication")
                .getProtectionDomain().getCodeSource().getLocation().toURI();
        Path classesRoot = Path.of(classesUri);
        assertThat(classesRoot).isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(classFile -> {
                String relative = classesRoot.relativize(classFile).toString().replace('\\', '/');
                try {
                    if (referencesVendor(classFile) && !relative.startsWith(ALLOWED_DIR)) {
                        violations.add(relative);
                    }
                } catch (IOException error) {
                    throw new AssertionError("无法解析 class 文件：" + relative, error);
                }
            });
        }

        assertThat(violations)
                .as("以下类在适配边界之外引用了 DeepSeek 专有类型")
                .isEmpty();
    }

    /**
     * 解析 class 常量池，判断是否存在以供应商前缀开头的 Utf8 条目（类/包名）。
     */
    private boolean referencesVendor(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        Reader in = new Reader(bytes);

        in.skip(4); // magic
        in.skip(2); // minor version
        in.skip(2); // major version
        int count = in.u2();

        for (int index = 1; index < count; index++) {
            int tag = in.u1();
            switch (tag) {
                case 1 -> { // Utf8
                    int length = in.u2();
                    String text = new String(bytes, in.position, length, java.nio.charset.StandardCharsets.UTF_8);
                    in.skip(length);
                    if (text.startsWith(VENDOR_PREFIX)) {
                        return true;
                    }
                }
                case 3, 4, 9, 10, 11, 17, 18 -> in.skip(4); // Integer/Float/refs/Dynamic
                case 5, 6 -> { // Long/Double 占两个常量槽
                    in.skip(8);
                    index++;
                }
                case 7, 8, 16, 19, 20 -> in.skip(2); // Class/String/MethodType/Module/Package
                case 15 -> in.skip(3); // MethodHandle
                case 12 -> in.skip(4); // NameAndType
                default -> throw new IOException("未知常量池 tag=" + tag + "，文件=" + classFile);
            }
        }
        return false;
    }

    /** 最小字节读取器。 */
    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int u1() {
            return data[position++] & 0xFF;
        }

        int u2() {
            int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        void skip(int n) {
            position += n;
        }
    }
}
