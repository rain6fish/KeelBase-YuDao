// SPDX-License-Identifier: Apache-2.0
package cn.iocoder.yudao.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 问这条边界是不是在它自己说的地方：适配器拥有的每个文件，要么是某个 Spec 产出的、要么是声明为手写的，
 * **没有第三种**。
 *
 * <p>它**两个方向都报错**：生成的缺了 ⇒ Spec 不再是源；两边都不属于 ⇒ 出现了**第二个源**——那正是这道检查
 * 存在的理由。命名空间与手写清单住在 {@link SpecCodegen}（`GENERATED_DIR` / `HAND_WRITTEN`），本类只做那次
 * 核对，故「哪些文件归适配器」这句话只有一处。
 *
 * <p>Asks whether the boundary is where it says it is: every file the adapter owns is either something a
 * Spec produces, or something declared hand-written, and nothing else. It fails in <b>both</b>
 * directions. The namespace and the hand-written list live in {@link SpecCodegen} (`GENERATED_DIR` /
 * `HAND_WRITTEN`); this class only performs the check, so the statement of "what this adapter owns" has
 * one home.
 */
final class Boundary {

    private Boundary() {
    }

    static void check(Path root) throws IOException {
        String genDir = String.join("/", SpecCodegen.GENERATED_DIR);
        List<String> generated = new ArrayList<>();
        try (var specs = Files.list(root.resolve("specs"))) {
            for (Path spec : specs.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Spec s = Spec.read(spec);
                generated.add("sql/keelbase-" + s.module() + ".sql");
                generated.add(genDir + "/" + s.entity() + "Tools.java");
                generated.add(genDir + "/" + s.entity() + "Governance.java");
            }
        }
        List<String> actual = new ArrayList<>();
        try (var sql = Files.list(root.resolve("sql"))) {
            sql.filter(p -> p.getFileName().toString().startsWith("keelbase-")
                            && p.getFileName().toString().endsWith(".sql"))
                    .sorted().forEach(p -> actual.add("sql/" + p.getFileName()));
        }
        try (var gen = Files.list(root.resolve(genDir))) {
            gen.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(p -> actual.add(genDir + "/" + p.getFileName()));
        }

        int bad = 0;
        for (String rel : actual) {
            if (generated.contains(rel)) {
                System.out.println("  generated     " + rel);
            } else if (SpecCodegen.HAND_WRITTEN.contains(rel)) {
                System.out.println("  hand-written  " + rel);
            } else {
                System.err.println("  UNDECLARED    " + rel
                        + " - neither a Spec's output nor declared hand-written");
                bad += 1;
            }
        }
        for (String rel : generated) {
            if (!actual.contains(rel)) {
                System.err.println("  MISSING       " + rel);
                bad += 1;
            }
        }
        for (String rel : SpecCodegen.HAND_WRITTEN) {
            if (!actual.contains(rel)) {
                System.err.println("  MISSING       " + rel + " (declared hand-written)");
                bad += 1;
            }
        }
        System.out.println();
        if (bad == 0) {
            System.out.println("boundary holds: " + generated.size() + " generated, "
                    + SpecCodegen.HAND_WRITTEN.size() + " hand-written, nothing else.");
        } else {
            System.err.println(bad + " file(s) outside the boundary - a Spec is not the source,"
                    + " or a second one appeared.");
            System.exit(1);
        }
    }
}
