package ai.venice.adstripper;

import com.android.apksig.ApkSigner;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.*;

import java.io.*;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

public final class AdPatcher {

    public interface Progress { void step(String message); }

    private static final Provider BC = new BouncyCastleProvider();

    private static final String VIEW = "Landroid/view/View;";
    private static final String META = "META-INF/";

    private AdPatcher() { }

    public static void patch(File inApk, File outApk, boolean aggressive, Progress p) throws Exception {
        Map<String, byte[]> patched = new LinkedHashMap<>();

        try (ZipFile zip = new ZipFile(inApk)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (isDex(ze.getName())) {
                    p.step("Patching " + ze.getName() + " ...");
                    patched.put(ze.getName(), patchDex(readAll(zip.getInputStream(ze)), aggressive));
                }
            }
        }
        if (patched.isEmpty()) throw new IOException("No classes.dex found in this APK.");

        File dir = outApk.getParentFile();
        File unsigned = new File(dir, outApk.getName() + ".unsigned");
        File signed   = new File(dir, outApk.getName() + ".signed");

        p.step("Repacking APK ...");
        repack(inApk, unsigned, patched);

        p.step("Signing APK ...");
        sign(unsigned, signed, new File(dir, "adstripper.p12"));
        unsigned.delete();

        if (outApk.exists()) outApk.delete();
        if (!signed.renameTo(outApk)) { copyFile(signed, outApk); signed.delete(); }
    }

    private static byte[] patchDex(byte[] raw, boolean aggressive) throws IOException {
        File in = File.createTempFile("adstrip_in", ".dex");
        File out = File.createTempFile("adstrip_out", ".dex");
        try {
            writeAll(in, raw);
            DexFile dex = DexFileFactory.loadDexFile(in, Opcodes.getDefault());
            DexFile rewritten = makeRewriter(aggressive).getDexFileRewriter().rewrite(dex);
            DexFileFactory.writeDexFile(out.getAbsolutePath(), rewritten);
            return readAll(new FileInputStream(out));
        } finally {
            in.delete();
            out.delete();
        }
    }

    private static DexRewriter makeRewriter(final boolean aggressive) {
        RewriterModule module = new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return new MethodRewriter(rewriters) {
                    @Override public Method rewrite(Method value) {
                        return new RewrittenMethod(value) {
                            @Override public MethodImplementation getImplementation() {
                                MethodImplementation impl = super.getImplementation();
                                if (impl == null) return null;
                                SdkRules.Action a = SdkRules.actionFor(
                                        method.getDefiningClass(), method.getName(), aggressive);
                                return a == null ? impl : stub(method, a);
                            }
                        };
                    }
                };
            }
        };
        return new DexRewriter(module);
    }

    private static MethodImplementation stub(Method m, SdkRules.Action action) {
        int paramRegs = isStatic(m) ? 0 : 1;
        for (CharSequence t : m.getParameterTypes()) {
            String d = t.toString();
            paramRegs += (d.equals("J") || d.equals("D")) ? 2 : 1;
        }
        String ret = m.getReturnType();
        boolean wide = ret.equals("J") || ret.equals("D");
        int locals = wide ? 2 : 1;
        int total = paramRegs + locals;
        int v0 = 0;
        int thisReg = locals;

        MethodImplementationBuilder b = new MethodImplementationBuilder(total);

        if (action == SdkRules.Action.HIDE && !isStatic(m)) {
            b.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, v0, 8));
            ImmutableMethodReference setVis = new ImmutableMethodReference(
                    VIEW, "setVisibility", Collections.singletonList("I"), "V");
            b.addInstruction(new BuilderInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 2, thisReg, v0, 0, 0, 0, setVis));
        }

        if (ret.equals("V")) {
            b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        } else if (wide) {
            b.addInstruction(new BuilderInstruction21s(Opcode.CONST_WIDE_16, v0, 0));
            b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_WIDE, v0));
        } else if ("ZBSIC".contains(ret)) {
            b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, v0, 0));
            b.addInstruction(new BuilderInstruction11x(Opcode.RETURN, v0));
        } else {
            b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, v0, 0));
            b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, v0));
        }
        return b.getMethodImplementation();
    }

    private static void repack(File in, File out, Map<String, byte[]> replacements) throws IOException {
        Counter counter = new Counter(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
        ZipOutputStream zos = new ZipOutputStream(counter);

        try (ZipFile zip = new ZipFile(in)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry src = en.nextElement();
                String name = src.getName();
                if (isOldSignature(name)) continue;

                ZipEntry dst = new ZipEntry(name);
                long t = src.getTime();
                if (t > 0) dst.setTime(t);

                byte[] data = null;
                if (replacements.containsKey(name)) {
                    data = replacements.get(name);
                    dst.setMethod(ZipEntry.DEFLATED);
                } else if (src.getMethod() == ZipEntry.STORED) {
                    data = readAll(zip.getInputStream(src));
                    dst.setMethod(ZipEntry.STORED);
                    dst.setSize(data.length);
                    dst.setCrc(crc32(data));
                } else {
                    dst.setMethod(ZipEntry.DEFLATED);
                }

                if (dst.getMethod() == ZipEntry.STORED) {
                    int align = name.endsWith(".so") ? 4096 : 4;
                    int base = (int) counter.count + 30 + utf8Len(name);
                    byte[] extra = alignExtra(base, align);
                    if (extra != null) dst.setExtra(extra);
                }

                zos.putNextEntry(dst);
                if (data != null) {
                    zos.write(data);
                } else {
                    try (InputStream is = zip.getInputStream(src)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        zos.finish();
        zos.flush();
        counter.flush();
    }

    private static boolean isStatic(Method m) {
        return (m.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0;
    }

    private static byte[] alignExtra(int base, int align) {
        int e = (align - (base % align)) % align;
        if (e == 0) return null;
        if (e < 4) e += align;
        byte[] extra = new byte[e];
        extra[0] = (byte) 0xCE; extra[1] = (byte) 0xFA;
        int payload = e - 4;
        extra[2] = (byte) (payload & 0xFF);
        extra[3] = (byte) ((payload >> 8) & 0xFF);
        return extra;
    }

    private static void sign(File in, File out, File ksFile) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] pass = "adstripper".toCharArray();

        PrivateKey key;
        List<X509Certificate> chain = new ArrayList<>();

        if (ksFile.exists()) {
            try (InputStream is = new FileInputStream(ksFile)) { ks.load(is, pass); }
            key = (PrivateKey) ks.getKey("adstripper", pass);
            for (Certificate c : ks.getCertificateChain("adstripper")) chain.add((X509Certificate) c);
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            long now = System.currentTimeMillis();
            X500Name dn = new X500Name("CN=AdStripper, O=AdStripper, C=US");
            JcaX509v3CertificateBuilder cb = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.ONE, new Date(now - 86_400_000L),
                    new Date(now + 30L * 365 * 86_400_000L), dn, kp.getPublic());
            ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BC).build(kp.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BC).getCertificate(cb.build(cs));

            key = kp.getPrivate();
            chain.add(cert);

            ks.load(null, pass);
            ks.setKeyEntry("adstripper", key, pass, new Certificate[]{cert});
            try (OutputStream os = new FileOutputStream(ksFile)) { ks.store(os, pass); }
        }

        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder("adstripper", key, chain).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    private static boolean isDex(String name) {
        return name.matches("^classes\\d*\\.dex$");
    }

    private static boolean isOldSignature(String name) {
        String u = name.toUpperCase(Locale.ROOT);
        if (!u.startsWith(META)) return false;
        return u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA")
                || u.endsWith(".EC") || u.equals(META + "MANIFEST.MF");
    }

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
        }
        return n;
    }

    private static long crc32(byte[] d) {
        CRC32 c = new CRC32();
        c.update(d);
        return c.getValue();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void writeAll(File f, byte[] d) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) { os.write(d); }
    }

    private static void copyFile(File a, File b) throws IOException {
        try (InputStream is = new FileInputStream(a); OutputStream os = new FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static final class Counter extends FilterOutputStream {
        long count;
        Counter(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
    }
}
