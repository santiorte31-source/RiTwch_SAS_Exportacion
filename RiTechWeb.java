import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.*;

/**
 * RiTech SAS · Exportaciones de café y cacao — versión WEB en Java puro.
 * Sin librerías externas: usa el servidor HTTP incluido en el JDK.
 * Ejecutar:  java RiTechWeb.java   (o: javac RiTechWeb.java && java RiTechWeb)
 * Abrir:     http://localhost:8080      (otro puerto: java RiTechWeb.java 9090)
 * Cuentas:   admin / Admin2026  ·  encargado / Encargado2026
 * Los datos viven en memoria (se reinician al apagar el servidor). Dinero en centavos (long).
 */
public class RiTechWeb {

    // ======================= Constantes =======================
    static final Map<String, String> TIPO = Map.of("CAFE", "Café", "CACAO", "Cacao");
    static final long MAXT = 99_999_999_999_999L;
    static final Map<String, String[]> VAT = new HashMap<>();
    static {
        VAT.put("DE", new String[]{"DE\\d{9}", "DE123456789"});
        VAT.put("NL", new String[]{"NL\\d{9}B\\d{2}", "NL123456789B01"});
        VAT.put("BE", new String[]{"BE[01]\\d{9}", "BE0123456789"});
        VAT.put("FR", new String[]{"FR[A-Z0-9]{2}\\d{9}", "FR12345678901"});
        VAT.put("IT", new String[]{"IT\\d{11}", "IT12345678901"});
        VAT.put("ES", new String[]{"ES[A-Z0-9]\\d{7}[A-Z0-9]", "ESA12345678"});
        VAT.put("PT", new String[]{"PT\\d{9}", "PT123456789"});
        VAT.put("AT", new String[]{"ATU\\d{8}", "ATU12345678"});
        VAT.put("PL", new String[]{"PL\\d{10}", "PL1234567890"});
        VAT.put("SE", new String[]{"SE\\d{12}", "SE123456789012"});
        VAT.put("DK", new String[]{"DK\\d{8}", "DK12345678"});
        VAT.put("FI", new String[]{"FI\\d{8}", "FI12345678"});
    }
    static final String[][] COV = {
        {"RF-001", "Registrar producto", "ok"}, {"RF-002", "Consultar productos", "ok"},
        {"RF-003", "Registrar cliente (formato de identificación fiscal)", "ok"},
        {"RF-004", "Consultar clientes", "ok"}, {"RF-005", "Registrar exportación", "ok"},
        {"RF-006", "Calcular valor total", "ok"}, {"RF-007", "Consultar exportaciones", "ok"},
        {"RF-008", "Modificar y eliminar con confirmación", "ok"}, {"RF-009", "Validar datos de entrada", "ok"},
        {"RF-010", "Iniciar sesión por rol", "sim"}, {"RF-011", "Validar VAT con VIES", "post"},
        {"RF-012", "Consultar países con REST Countries", "post"}, {"RF-013", "Convertir USD a EUR", "post"},
        {"RF-014", "Resumen de exportaciones", "ok"}};
    static final Map<String, String> COVL = Map.of("ok", "Incluido", "sim", "Simulado", "post", "Sprint posterior");

    // ======================= Modelo =======================
    static class Usuario { int id; String nombre, hash, rol;
        Usuario(int i, String n, String h, String r) { id = i; nombre = n; hash = h; rol = r; }
        boolean admin() { return "ADMINISTRADOR".equals(rol); } }
    static class Pais { int id; String iso, nombre, region, moneda;
        Pais(int i, String c, String n, String r, String m) { id = i; iso = c; nombre = n; region = r; moneda = m; } }
    static class Producto { int id; String tipo, variedad; long precioKg;
        Producto(int i, String t, String v, long p) { id = i; tipo = t; variedad = v; precioKg = p; } }
    static class Cliente { int id, idPais; String empresa, nif, contacto, correo, telefono; }
    static class Exportacion { int id, idProducto, idCliente, idPais; long volumenKg, precioAplicado, valorTotal; String fecha; }
    static class Db {
        List<Usuario> users = new ArrayList<>(); List<Pais> paises = new ArrayList<>();
        List<Producto> productos = new ArrayList<>(); List<Cliente> clientes = new ArrayList<>();
        List<Exportacion> exp = new ArrayList<>(); int seqProducto = 5, seqCliente = 5, seqExport = 7;
    }
    static Db db = seed();
    static final Map<String, Usuario> SES = new HashMap<>();

    // ======================= Utilidades (misma lógica que la versión Swing) =======================
    record R(Long v, String e) {}

    static String fmt(long c) {
        String s = String.format("%03d", Math.abs(c));
        return s.substring(0, s.length() - 2).replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".") + "," + s.substring(s.length() - 2);
    }
    static String usd(long c) { return "US$ " + fmt(c); }
    static String plain(long c) { return fmt(c).replace(".", ""); }
    static String fd(String s) { String[] p = s.split("-"); return p[2] + "/" + p[1] + "/" + p[0]; }
    static String pn(Producto p) { return TIPO.get(p.tipo) + " " + p.variedad; }
    static Long calc(long v, long p) {
        BigInteger t = BigInteger.valueOf(v).multiply(BigInteger.valueOf(p)).add(BigInteger.valueOf(50)).divide(BigInteger.valueOf(100));
        return t.compareTo(BigInteger.valueOf(MAXT)) > 0 ? null : t.longValue();
    }
    static R dec(String s, int ints) {
        s = s == null ? "" : s.trim().replace(',', '.');
        if (s.isEmpty()) return new R(null, "vacio");
        if (s.startsWith("-")) return new R(null, "pos");
        if (!s.matches("\\d+(\\.\\d+)?")) return new R(null, "num");
        String[] p = s.split("\\.", -1); String i = p[0], f = p.length > 1 ? p[1] : "";
        if (f.length() > 2) return new R(null, "dec");
        if (i.replaceFirst("^0+", "").length() > ints) return new R(null, "max");
        return new R(Long.parseLong(i) * 100 + Long.parseLong((f + "00").substring(0, 2)), null);
    }
    static R num(String raw, int ints, String name) {
        R r = dec(raw, ints);
        if (r.e() != null) return new R(null, switch (r.e()) {
            case "vacio" -> name + " es obligatorio.";
            case "pos" -> name + " debe ser mayor a cero.";
            case "num" -> name + " debe ser un número (use punto o coma para los decimales).";
            case "dec" -> name + " admite máximo 2 decimales.";
            default -> name + " supera el máximo permitido (" + ints + " dígitos enteros).";
        });
        return r.v() <= 0 ? new R(null, name + " debe ser mayor a cero.") : r;
    }
    static boolean validDate(String s) {
        if (!s.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try { int y = LocalDate.parse(s).getYear(); return y >= 2000 && y <= 2100; } catch (Exception e) { return false; }
    }
    static String hash(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(("ritech:" + s).getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Producto prod(int id) { return db.productos.stream().filter(x -> x.id == id).findFirst().orElse(null); }
    static Cliente cli(int id) { return db.clientes.stream().filter(x -> x.id == id).findFirst().orElse(null); }
    static Pais pais(int id) { return db.paises.stream().filter(x -> x.id == id).findFirst().orElse(null); }
    static Exportacion exp(int id) { return db.exp.stream().filter(x -> x.id == id).findFirst().orElse(null); }
    static int toInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    static String h(Object o) { return o == null ? "" : o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ======================= Datos de ejemplo =======================
    static Db seed() {
        Db d = new Db();
        String[][] pa = {{"DE", "Alemania", "Europa Occidental", "EUR"}, {"NL", "Países Bajos", "Europa Occidental", "EUR"},
            {"BE", "Bélgica", "Europa Occidental", "EUR"}, {"FR", "Francia", "Europa Occidental", "EUR"},
            {"IT", "Italia", "Europa Meridional", "EUR"}, {"ES", "España", "Europa Meridional", "EUR"},
            {"PT", "Portugal", "Europa Meridional", "EUR"}, {"AT", "Austria", "Europa Central", "EUR"},
            {"PL", "Polonia", "Europa Central", "PLN"}, {"SE", "Suecia", "Europa Septentrional", "SEK"},
            {"DK", "Dinamarca", "Europa Septentrional", "DKK"}, {"FI", "Finlandia", "Europa Septentrional", "EUR"}};
        for (int i = 0; i < pa.length; i++) d.paises.add(new Pais(i + 1, pa[i][0], pa[i][1], pa[i][2], pa[i][3]));
        d.productos.add(new Producto(1, "CAFE", "Castillo", 685)); d.productos.add(new Producto(2, "CAFE", "Caturra", 720));
        d.productos.add(new Producto(3, "CACAO", "Criollo", 940)); d.productos.add(new Producto(4, "CACAO", "CCN-51", 560));
        Object[][] cl = {{"Nordhafen Kaffeerösterei GmbH", 1, "DE298765431", "Anja Keller", "anja.keller@nordhafen.example", "+49 40 5550 1201"},
            {"Amsterdam Cacao Traders B.V.", 2, "NL864123987B01", "Joost de Vries", "j.devries@acacao.example", "+31 20 555 0142"},
            {"Chocolaterie Lumière SAS", 4, "FR40303265045", "Camille Laurent", "c.laurent@lumiere.example", "+33 1 55 50 01 77"},
            {"Torrefazione Alba S.r.l.", 5, "IT01234567897", "Marco Bellini", "m.bellini@alba.example", "+39 06 5550 0188"}};
        for (int i = 0; i < cl.length; i++) {
            Cliente c = new Cliente(); c.id = i + 1; c.empresa = (String) cl[i][0]; c.idPais = (int) cl[i][1];
            c.nif = (String) cl[i][2]; c.contacto = (String) cl[i][3]; c.correo = (String) cl[i][4]; c.telefono = (String) cl[i][5];
            d.clientes.add(c);
        }
        Object[][] ex = {{1, 1, 1, 1200000L, "2026-09-03"}, {3, 3, 4, 350050L, "2026-09-08"}, {2, 2, 2, 820000L, "2026-09-12"},
            {4, 2, 3, 540025L, "2026-09-17"}, {1, 4, 5, 1500000L, "2026-09-22"}, {3, 1, 1, 275000L, "2026-09-28"}};
        for (int i = 0; i < ex.length; i++) {
            Exportacion x = new Exportacion(); Producto p = d.productos.get((int) ex[i][0] - 1);
            x.id = i + 1; x.idProducto = p.id; x.idCliente = (int) ex[i][1]; x.idPais = (int) ex[i][2];
            x.volumenKg = (long) ex[i][3]; x.fecha = (String) ex[i][4]; x.precioAplicado = p.precioKg; x.valorTotal = calc(x.volumenKg, p.precioKg);
            d.exp.add(x);
        }
        d.users.add(new Usuario(1, "admin", hash("Admin2026"), "ADMINISTRADOR"));
        d.users.add(new Usuario(2, "encargado", hash("Encargado2026"), "ENCARGADO"));
        return d;
    }

    // ======================= Servidor HTTP =======================
    static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
    static String sid(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c != null) for (String p : c.split(";")) if (p.trim().startsWith("sid=")) return p.trim().substring(4);
        return "";
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer srv = HttpServer.create(new InetSocketAddress(port), 0);
        srv.createContext("/", ex -> {
            String out; int code = 200;
            try {
                Map<String, String> q = parse(ex.getRequestURI().getRawQuery());
                Map<String, String> f = ex.getRequestMethod().equals("POST") ? parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : Map.of();
                out = route(ex, ex.getRequestURI().getPath(), q, f, SES.get(sid(ex)));
            } catch (Exception e) { e.printStackTrace(); out = "<h1>Error interno</h1><pre>" + h(e) + "</pre>"; code = 500; }
            if (out.startsWith("REDIRECT:")) {
                ex.getResponseHeaders().add("Location", out.substring(9)); ex.sendResponseHeaders(302, -1); ex.close(); return;
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        srv.start();
        System.out.println("RiTech SAS web activo en  http://localhost:" + port + "   (Ctrl+C para detener)");
    }

    static String flash(String msg, boolean bad) { return "?m=" + enc(msg) + (bad ? "&e=1" : ""); }

    static String route(HttpExchange ex, String p, Map<String, String> q, Map<String, String> f, Usuario u) {
        boolean post = ex.getRequestMethod().equals("POST");
        if (p.equals("/login")) {
            if (!post) return login(List.of(), "");
            String name = f.getOrDefault("u", "").trim().toLowerCase(), pw = f.getOrDefault("p", "");
            List<String> e = new ArrayList<>();
            if (name.isEmpty()) e.add("Ingrese el nombre de usuario.");
            if (pw.isEmpty()) e.add("Ingrese la contraseña.");
            Usuario us = db.users.stream().filter(x -> x.nombre.equals(name)).findFirst().orElse(null);
            if (e.isEmpty() && (us == null || !us.hash.equals(hash(pw)))) e.add("Usuario o contraseña incorrectos.");
            if (!e.isEmpty()) return login(e, name);
            String id = UUID.randomUUID().toString(); SES.put(id, us);
            ex.getResponseHeaders().add("Set-Cookie", "sid=" + id + "; Path=/; HttpOnly");
            return "REDIRECT:/resumen";
        }
        if (u == null) return "REDIRECT:/login";
        switch (p) {
            case "/logout": SES.remove(sid(ex)); return "REDIRECT:/login";
            case "/reset":
                if (post && u.admin()) { db = seed(); SES.replaceAll((k, v) -> db.users.stream().filter(x -> x.nombre.equals(v.nombre)).findFirst().orElse(v));
                    return "REDIRECT:/resumen" + flash("Datos de ejemplo restablecidos.", false); }
                return "REDIRECT:/resumen";
            case "/eliminar": return eliminar(u, f);
            case "/exportaciones": return exportaciones(u, q);
            case "/nueva": return nueva(u, q, f, post);
            case "/productos": return productos(u, q);
            case "/producto": return prodForm(u, q, f, post);
            case "/clientes": return clientes(u, q);
            case "/cliente": return cliForm(u, q, f, post);
            case "/paises": return paises(u, q);
            case "/cobertura": return cobertura(u, q);
            default: return resumen(u, q);
        }
    }

    // ======================= Plantilla HTML =======================
    static final String CSS = """
        :root{--bg:#F1F3EF;--side:#2A1D1B;--sink:#EFE6E1;--ac:#2F6B4F;--gold:#B8862B;--cacao:#7A4A32;--eu:#27458F;--mut:#5E6A63;--line:#D9DED7;--bad:#B3261E}
        *{box-sizing:border-box}body{margin:0;font-family:system-ui,Segoe UI,Arial,sans-serif;background:var(--bg);color:#1c1f1d;display:flex;min-height:100vh}
        aside{width:240px;background:var(--side);color:var(--sink);padding:18px 12px;display:flex;flex-direction:column;position:sticky;top:0;height:100vh}
        aside h2{margin:0 6px}aside small{margin:0 6px 14px;display:block}
        aside a.n{color:var(--sink);text-decoration:none;padding:9px 10px;border-radius:6px;margin-bottom:2px;display:block}
        aside a.n.on{background:var(--ac)}aside a.n:hover{background:#3d2c29}aside a.n.on:hover{background:var(--ac)}
        .who{margin-top:auto;padding:8px 6px;font-size:14px}aside form{margin:4px 0}aside button{width:100%}
        main{flex:1;padding:22px 28px;min-width:0}.hd{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;margin-bottom:14px}
        h1{margin:0;font-size:24px}.sub{color:var(--mut);font-size:13px;margin-top:2px}
        .b,button{font:inherit;font-size:13px;padding:7px 12px;border:1px solid var(--line);background:#fff;border-radius:6px;cursor:pointer;text-decoration:none;color:#000;display:inline-block}
        .b.p,button.p{background:var(--ac);color:#fff;border-color:var(--ac)}.b.d,button.d{color:var(--bad)}
        .card{background:#fff;border:1px solid var(--line);border-radius:8px;padding:14px 16px}
        .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin-bottom:14px}
        .kpis .card span{color:var(--mut);font-size:12px}.kpis .card b{display:block;font-size:20px;margin-top:4px}.kpis .main{border:2px solid var(--ac)}
        .g2{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:12px;margin-bottom:14px}
        .bar{display:grid;grid-template-columns:110px 1fr 130px;gap:10px;align-items:center;margin:9px 0;font-size:13px}
        .bar i{display:block;height:12px;border-radius:6px}.bar div{background:var(--bg);border-radius:6px}.bar em{text-align:right;font-style:normal}
        .tw{overflow-x:auto;background:#fff;border:1px solid var(--line);border-radius:8px}
        table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
        th{background:#f7f8f6}td.r,th.r{text-align:right}.inl{display:inline}
        .flash{padding:10px 14px;border-radius:6px;margin-bottom:12px;background:#E3F1EA;color:var(--ac);border:1px solid #b9d9c9}.flash.bad{background:#FBE6E4;color:var(--bad);border-color:#efbcb7}
        label{display:block;font-weight:600;font-size:12px;margin:10px 0 3px}input,select{width:100%;padding:8px;border:1px solid #bfc6bd;border-radius:6px;font:inherit}
        .two{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px;align-items:start}
        .tot{background:var(--side);color:var(--sink);border-radius:8px;padding:18px}.tot big{display:block;font-size:30px;font-weight:700;color:#E3B655;margin:6px 0 10px}
        .bar2{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}.bar2 input{width:260px}.bar2 select{width:200px}
        .hint{font-size:12px;color:var(--mut);font-style:italic;margin-top:3px}.errs{background:#FBE6E4;border:1px solid #efbcb7;color:var(--bad);padding:10px 14px 10px 28px;border-radius:6px;margin-bottom:10px}
        .login{margin:auto;width:340px}.mut{color:var(--mut);font-size:12px}.tag{padding:2px 8px;border-radius:10px;font-size:12px;background:#E3F1EA;color:var(--ac)}.tag.sim{background:#FFF3D6;color:#8a6414}.tag.post{background:#E8ECF7;color:var(--eu)}
        @media(max-width:760px){body{flex-direction:column}aside{width:100%;height:auto;position:static}main{padding:16px}}
        """;
    static final String[][] NAV = {{"resumen", "Resumen"}, {"exportaciones", "Exportaciones"}, {"nueva", "Nueva exportación"},
        {"productos", "Productos"}, {"clientes", "Clientes"}, {"paises", "Países de destino"}, {"cobertura", "Cobertura"}};

    static String shell(String title, String body) {
        return "<!DOCTYPE html><html lang=es><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>"
            + h(title) + " · RiTech SAS</title><style>" + CSS + "</style></head><body>" + body + "</body></html>";
    }

    static String layout(Usuario u, String view, Map<String, String> q, String title, String sub, String actions, String body) {
        Map<String, Integer> cnt = Map.of("exportaciones", db.exp.size(), "productos", db.productos.size(), "clientes", db.clientes.size());
        StringBuilder s = new StringBuilder("<aside><h2>RiTech SAS</h2><small>Exportaciones UE</small>");
        for (String[] n : NAV) s.append("<a class='n").append(view.equals(n[0]) ? " on" : "").append("' href='/").append(n[0]).append("'>")
            .append(h(n[1])).append(cnt.containsKey(n[0]) ? "  (" + cnt.get(n[0]) + ")" : "").append("</a>");
        s.append("<div class=who><b>").append(h(u.nombre)).append("</b><br>").append(u.admin() ? "Administrador" : "Encargado de exportaciones").append("</div>");
        if (u.admin()) s.append("<form method=post action=/reset onsubmit=\"return confirm('Se borrarán todos los registros actuales y se cargarán los datos de ejemplo iniciales.')\"><button>Restablecer datos</button></form>");
        s.append("<form action=/logout><button>Cerrar sesión</button></form></aside><main>");
        if (q.containsKey("m")) s.append("<div class='flash").append(q.containsKey("e") ? " bad" : "").append("'>").append(h(q.get("m"))).append("</div>");
        s.append("<div class=hd><div><h1>").append(h(title)).append("</h1><div class=sub>").append(h(sub)).append("</div></div><div>").append(actions).append("</div></div>")
            .append(body).append("</main>");
        return shell(title, s.toString());
    }

    static String tbl(String[] heads, List<String[]> rows, Set<String> right) {
        StringBuilder s = new StringBuilder("<div class=tw><table><tr>");
        for (String x : heads) s.append("<th").append(right.contains(x) ? " class=r" : "").append(">").append(h(x)).append("</th>");
        s.append("</tr>");
        if (rows.isEmpty()) s.append("<tr><td colspan=").append(heads.length).append(" class=mut>No hay registros para mostrar.</td></tr>");
        for (String[] r : rows) {
            s.append("<tr>");
            for (int i = 0; i < r.length; i++) s.append("<td").append(i < heads.length && right.contains(heads[i]) ? " class=r" : "").append(">")
                .append(r[i].startsWith("<") ? r[i] : h(r[i])).append("</td>");
            s.append("</tr>");
        }
        return s.append("</table></div>").toString();
    }
    static String acts(Usuario u, String editPath, String kind, int id) {
        if (!u.admin()) return "";
        return "<a class=b href='/" + editPath + "?id=" + id + "'>Editar</a> <form method=post action=/eliminar class=inl onsubmit=\"return confirm('¿Eliminar este registro? Esta acción no se puede deshacer.')\">"
            + "<input type=hidden name=k value=" + kind + "><input type=hidden name=id value=" + id + "><button class='b d'>Eliminar</button></form>";
    }
    static String[] withActs(Usuario u, String... heads) {
        if (!u.admin()) return heads;
        String[] r = Arrays.copyOf(heads, heads.length + 1); r[heads.length] = "Acciones"; return r;
    }
    static String[] row(Usuario u, String acts, String... cells) {
        if (!u.admin()) return cells;
        String[] r = Arrays.copyOf(cells, cells.length + 1); r[cells.length] = acts; return r;
    }
    static String note(Usuario u) { return u.admin() ? "" : "<p class=mut>Solo el administrador puede modificar o eliminar registros.</p>"; }
    static String errs(List<String> e) {
        if (e.isEmpty()) return "";
        return "<ul class=errs>" + e.stream().map(x -> "<li>" + h(x) + "</li>").collect(Collectors.joining()) + "</ul>";
    }
    static String opts(List<String[]> items, String sel) { // {id, label, extraAttrs}
        StringBuilder s = new StringBuilder("<option value=0>Seleccione…</option>");
        for (String[] i : items) s.append("<option value=").append(i[0]).append(i[0].equals(sel) ? " selected" : "").append(i.length > 2 ? " " + i[2] : "").append(">").append(h(i[1])).append("</option>");
        return s.toString();
    }
    static List<String[]> paisItems() {
        return db.paises.stream().sorted(Comparator.comparing((Pais x) -> x.nombre)).map(x -> new String[]{"" + x.id, x.nombre,
            "data-h='" + h("Formato para " + x.nombre + ", ej.: " + VAT.get(x.iso)[1]) + "'"}).collect(Collectors.toList());
    }

    // ======================= Login =======================
    static String login(List<String> e, String user) {
        return shell("Iniciar sesión", "<div class='card login'><h1 style='font-size:22px'>RiTech SAS</h1><div class=sub>Exportación de café y cacao a la UE</div>"
            + errs(e) + "<form method=post action=/login><label>Usuario</label><input name=u value='" + h(user) + "' id=u autofocus>"
            + "<label>Contraseña</label><input type=password name=p id=p><br><button class=p style='width:100%'>Iniciar sesión</button></form>"
            + "<p class=mut>Prueba: <button type=button onclick=\"u.value='admin';p.value='Admin2026'\">Administrador</button> "
            + "<button type=button onclick=\"u.value='encargado';p.value='Encargado2026'\">Encargado</button></p></div>");
    }

    // ======================= Resumen =======================
    static String bars(String title, Map<String, Long> items, Map<String, String> colors) {
        StringBuilder s = new StringBuilder("<div class=card><b>" + h(title) + "</b>");
        long max = Math.max(1, items.values().stream().mapToLong(Long::longValue).max().orElse(1));
        if (items.isEmpty()) s.append("<p class=mut>Aún no hay datos para mostrar.</p>");
        items.forEach((k, v) -> s.append("<div class=bar><span>").append(h(k)).append("</span><div><i style='width:").append(Math.round(v * 100.0 / max))
            .append("%;background:").append(colors.getOrDefault(k, "var(--eu)")).append("'></i></div><em>").append(usd(v)).append("</em></div>"));
        return s.append("</div>").toString();
    }
    static String resumen(Usuario u, Map<String, String> q) {
        long tot = db.exp.stream().mapToLong(x -> x.valorTotal).sum(), kg = db.exp.stream().mapToLong(x -> x.volumenKg).sum();
        Map<String, Long> byT = new LinkedHashMap<>(), byP = new HashMap<>();
        for (Exportacion x : db.exp) { byT.merge(TIPO.get(prod(x.idProducto).tipo), x.valorTotal, Long::sum); byP.merge(pais(x.idPais).nombre, x.valorTotal, Long::sum); }
        Map<String, Long> top = byP.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        StringBuilder s = new StringBuilder("<div class=kpis><div class='card main'><span>Valor total exportado</span><b>" + usd(tot) + "</b></div>"
            + "<div class=card><span>Exportaciones registradas</span><b>" + db.exp.size() + "</b></div>"
            + "<div class=card><span>Volumen total</span><b>" + fmt(kg) + " kg</b></div>"
            + "<div class=card><span>Valor promedio por operación</span><b>" + usd(db.exp.isEmpty() ? 0 : Math.round((double) tot / db.exp.size())) + "</b></div></div>");
        s.append("<div class=g2>").append(bars("Valor por tipo de producto", byT, Map.of("Café", "var(--ac)", "Cacao", "var(--cacao)")))
            .append(bars("Principales países de destino", top, Map.of())).append("</div><h3>Últimas exportaciones</h3>");
        List<String[]> rows = db.exp.stream().sorted(Comparator.comparing((Exportacion x) -> x.fecha).thenComparingInt(x -> x.id).reversed()).limit(5)
            .map(x -> new String[]{fd(x.fecha), pn(prod(x.idProducto)), cli(x.idCliente).empresa, pais(x.idPais).nombre, usd(x.valorTotal)}).collect(Collectors.toList());
        s.append(tbl(new String[]{"Salida", "Producto", "Cliente", "Destino", "Valor total"}, rows, Set.of("Valor total")));
        return layout(u, "resumen", q, "Resumen de exportaciones", "Valores en dólares estadounidenses (USD), moneda base del sistema.",
            "<a class='b p' href=/nueva>Nueva exportación</a>", s.toString());
    }

    // ======================= Exportaciones =======================
    static String exportaciones(Usuario u, Map<String, String> q) {
        String fq = q.getOrDefault("q", "").trim().toLowerCase(), ft = q.getOrDefault("t", "");
        List<Exportacion> l = db.exp.stream().filter(x -> {
            Producto p = prod(x.idProducto);
            if (!ft.isEmpty() && !p.tipo.equals(ft)) return false;
            return fq.isEmpty() || String.join(" ", pn(p), cli(x.idCliente).empresa, pais(x.idPais).nombre, "#" + x.id, "" + x.id).toLowerCase().contains(fq);
        }).sorted(Comparator.comparing((Exportacion x) -> x.fecha).thenComparingInt(x -> x.id).reversed()).collect(Collectors.toList());
        String bar = "<form class=bar2 method=get><input name=q placeholder='Buscar por cliente, país, variedad o número' value='" + h(q.getOrDefault("q", "")) + "'>"
            + "<select name=t><option value=''>Todos los productos</option><option value=CAFE" + (ft.equals("CAFE") ? " selected" : "") + ">Café</option><option value=CACAO"
            + (ft.equals("CACAO") ? " selected" : "") + ">Cacao</option></select><button class=p>Filtrar</button><a class=b href=/exportaciones>Limpiar</a></form>";
        List<String[]> rows = l.stream().map(x -> row(u, acts(u, "nueva", "exp", x.id), "#" + x.id, fd(x.fecha), pn(prod(x.idProducto)), cli(x.idCliente).empresa,
            pais(x.idPais).nombre, fmt(x.volumenKg), usd(x.precioAplicado), usd(x.valorTotal))).collect(Collectors.toList());
        String body = bar + tbl(withActs(u, "N.º", "Salida", "Producto", "Cliente", "Destino", "Volumen (kg)", "Precio/kg aplicado", "Valor total"), rows,
            Set.of("Volumen (kg)", "Precio/kg aplicado", "Valor total"))
            + "<p class=mut>" + l.size() + " de " + db.exp.size() + " exportaciones · suma mostrada: " + usd(l.stream().mapToLong(x -> x.valorTotal).sum()) + "</p>" + note(u);
        return layout(u, "exportaciones", q, "Exportaciones", "Historial de operaciones con producto, cliente, destino, volumen y valor total.",
            "<a class='b p' href=/nueva>Nueva exportación</a>", body);
    }

    static String nueva(Usuario u, Map<String, String> q, Map<String, String> f, boolean post) {
        int eid = toInt((post ? f : q).getOrDefault("id", "0"));
        Exportacion x = eid == 0 ? null : exp(eid);
        if (eid != 0 && (x == null || !u.admin())) return "REDIRECT:/exportaciones" + flash("No puede editar ese registro.", true);
        String title = x != null ? "Editar exportación #" + x.id : "Nueva exportación";
        if (db.productos.isEmpty() || db.clientes.isEmpty())
            return layout(u, "nueva", q, title, "", "", "<div class=card>Antes de registrar una exportación debe haber al menos un producto y un cliente.<br><br><a class=b href=/productos>Ir a productos</a> <a class=b href=/clientes>Ir a clientes</a></div>");
        List<String> e = new ArrayList<>();
        Map<String, String> v = new HashMap<>(f);
        if (!post) {
            v.put("pr", x == null ? "0" : "" + x.idProducto); v.put("cl", x == null ? "0" : "" + x.idCliente); v.put("ds", x == null ? "0" : "" + x.idPais);
            v.put("vol", x == null ? "" : plain(x.volumenKg)); v.put("fe", x == null ? LocalDate.now().toString() : x.fecha);
        } else {
            Producto p = prod(toInt(f.getOrDefault("pr", "0"))); Cliente c = cli(toInt(f.getOrDefault("cl", "0"))); Pais d = pais(toInt(f.getOrDefault("ds", "0")));
            R vr = num(f.getOrDefault("vol", ""), 10, "El volumen en kg"); String fe = f.getOrDefault("fe", "").trim(); long price = 0, valor = 0;
            if (p == null) e.add("Seleccione un producto.");
            if (c == null) e.add("Seleccione un cliente.");
            if (d == null) e.add("Seleccione el país de destino.");
            if (vr.e() != null) e.add(vr.e());
            if (fe.isEmpty()) e.add("La fecha de salida es obligatoria."); else if (!validDate(fe)) e.add("La fecha de salida no es válida.");
            if (p != null && vr.v() != null) {
                price = x != null && x.idProducto == p.id ? x.precioAplicado : p.precioKg;
                Long t = calc(vr.v(), price);
                if (t == null) e.add("El valor total excede el máximo permitido (US$ 999.999.999.999,99). Reduzca el volumen."); else valor = t;
            }
            if (e.isEmpty()) {
                Exportacion n = x != null ? x : new Exportacion();
                if (x == null) { n.id = db.seqExport++; db.exp.add(n); }
                n.idProducto = p.id; n.idCliente = c.id; n.idPais = d.id; n.volumenKg = vr.v(); n.fecha = fe; n.precioAplicado = price; n.valorTotal = valor;
                return "REDIRECT:/exportaciones" + flash("Exportación #" + n.id + (x != null ? " actualizada" : " registrada") + ". Valor total: " + usd(valor) + ".", false);
            }
        }
        List<String[]> pi = db.productos.stream().map(p -> new String[]{"" + p.id, pn(p) + " · " + usd(p.precioKg) + "/kg",
            "data-p=" + (x != null && x.idProducto == p.id ? x.precioAplicado : p.precioKg)}).collect(Collectors.toList());
        List<String[]> ci = db.clientes.stream().map(c -> new String[]{"" + c.id, c.empresa + " (" + pais(c.idPais).iso + ")", "data-pais=" + c.idPais}).collect(Collectors.toList());
        String form = "<form method=post action=/nueva class=card>" + errs(e) + (x != null ? "<input type=hidden name=id value=" + x.id + ">" : "")
            + "<label>Producto</label><select name=pr id=pr onchange=upd()>" + opts(pi, v.getOrDefault("pr", "0")) + "</select>"
            + "<label>Cliente</label><select name=cl id=cl onchange=cc()>" + opts(ci, v.getOrDefault("cl", "0")) + "</select>"
            + "<label>País de destino</label><select name=ds id=ds>" + opts(paisItems(), v.getOrDefault("ds", "0")) + "</select>"
            + "<label>Volumen (kg) — mayor a cero, máx. 2 decimales</label><input name=vol id=vol value='" + h(v.getOrDefault("vol", "")) + "' oninput=upd()>"
            + "<label>Fecha de salida (AAAA-MM-DD)</label><input name=fe value='" + h(v.getOrDefault("fe", "")) + "' placeholder=AAAA-MM-DD><br>"
            + "<a class=b href=/exportaciones>Cancelar</a> <button class=p>" + (x != null ? "Guardar cambios" : "Confirmar y registrar") + "</button></form>";
        String side = "<div class=tot><small>Valor total (volumen × precio por kg)</small><big id=tot>—</big><div id=det></div>"
            + "<p><i>El precio queda guardado con la exportación y no cambia si el producto se reprecia.</i></p></div>";
        String js = "<script>function upd(){var o=pr.selectedOptions[0],v=parseFloat(vol.value.replace(',','.')),p=+(o.dataset.p||0);"
            + "tot.textContent=(v>0&&p)?'US$ '+(Math.round(v*p)/100).toLocaleString('de-DE',{minimumFractionDigits:2,maximumFractionDigits:2}):'—';"
            + "det.innerHTML='Producto: '+(p?o.text.split(' · ')[0]:'—')+'<br>Precio por kg aplicado: '+(p?'US$ '+(p/100).toFixed(2):'—')+'<br>Volumen: '+(v>0?v+' kg':'—')}"
            + "function cc(){var o=cl.selectedOptions[0];if(ds.value==0&&o.dataset.pais)ds.value=o.dataset.pais}upd()</script>";
        return layout(u, "nueva", q, title, "Complete los datos; el valor total se calcula automáticamente.", "", "<div class=two>" + form + side + "</div>" + js);
    }

    // ======================= Productos =======================
    static String productos(Usuario u, Map<String, String> q) {
        List<String[]> rows = db.productos.stream().sorted(Comparator.comparing((Producto p) -> p.tipo).thenComparing(p -> p.variedad))
            .map(p -> row(u, acts(u, "producto", "prod", p.id), TIPO.get(p.tipo), p.variedad, usd(p.precioKg), "" + db.exp.stream().filter(x -> x.idProducto == p.id).count())).collect(Collectors.toList());
        return layout(u, "productos", q, "Productos", "Catálogo de café y cacao. Cambiar un precio no altera exportaciones ya registradas.",
            "<a class='b p' href=/producto>Nuevo producto</a>", tbl(withActs(u, "Tipo", "Variedad", "Precio por kg", "Exportaciones"), rows, Set.of("Precio por kg", "Exportaciones")) + note(u));
    }
    static String prodForm(Usuario u, Map<String, String> q, Map<String, String> f, boolean post) {
        int id = toInt((post ? f : q).getOrDefault("id", "0")); Producto p = id == 0 ? null : prod(id);
        if (id != 0 && (p == null || !u.admin())) return "REDIRECT:/productos" + flash("No puede editar ese registro.", true);
        Map<String, String> v = new HashMap<>(f); List<String> e = new ArrayList<>();
        if (!post) { v.put("tp", p == null ? "" : p.tipo); v.put("va", p == null ? "" : p.variedad); v.put("pr", p == null ? "" : plain(p.precioKg)); }
        else {
            String tp = f.getOrDefault("tp", ""), va = f.getOrDefault("va", "").trim();
            if (!TIPO.containsKey(tp)) e.add("Seleccione el tipo de producto (café o cacao).");
            if (va.isEmpty()) e.add("La variedad es obligatoria."); else if (va.length() > 60) e.add("La variedad no puede superar 60 caracteres.");
            R pr = num(f.getOrDefault("pr", ""), 8, "El precio por kg"); if (pr.e() != null) e.add(pr.e());
            if (TIPO.containsKey(tp) && !va.isEmpty() && db.productos.stream().anyMatch(x -> (p == null || x.id != p.id) && x.tipo.equals(tp) && x.variedad.equalsIgnoreCase(va)))
                e.add("Ya existe un producto de ese tipo con esa variedad.");
            if (e.isEmpty()) {
                if (p == null) db.productos.add(new Producto(db.seqProducto++, tp, va, pr.v())); else { p.tipo = tp; p.variedad = va; p.precioKg = pr.v(); }
                return "REDIRECT:/productos" + flash(p == null ? "Producto registrado." : "Producto actualizado.", false);
            }
        }
        String form = "<form method=post action=/producto class=card style='max-width:480px'>" + errs(e) + (p != null ? "<input type=hidden name=id value=" + p.id + ">" : "")
            + "<label>Tipo</label><select name=tp><option value=''>Seleccione…</option><option value=CAFE" + ("CAFE".equals(v.get("tp")) ? " selected" : "") + ">Café</option><option value=CACAO"
            + ("CACAO".equals(v.get("tp")) ? " selected" : "") + ">Cacao</option></select>"
            + "<label>Variedad (ej.: Castillo, Criollo)</label><input name=va value='" + h(v.getOrDefault("va", "")) + "'>"
            + "<label>Precio por kg (USD) — mayor a cero, máx. 2 decimales</label><input name=pr value='" + h(v.getOrDefault("pr", "")) + "'><br>"
            + "<a class=b href=/productos>Cancelar</a> <button class=p>Guardar</button></form>";
        return layout(u, "productos", q, p == null ? "Nuevo producto" : "Editar producto", "", "", form);
    }

    // ======================= Clientes =======================
    static String clientes(Usuario u, Map<String, String> q) {
        List<String[]> rows = db.clientes.stream().sorted(Comparator.comparing((Cliente c) -> c.empresa)).map(c -> row(u, acts(u, "cliente", "cli", c.id), c.empresa, pais(c.idPais).nombre, c.nif,
            c.contacto + " · " + c.correo + (c.telefono != null ? " · " + c.telefono : ""), "" + db.exp.stream().filter(x -> x.idCliente == c.id).count())).collect(Collectors.toList());
        return layout(u, "clientes", q, "Clientes", "Compradores europeos disponibles para asociar a exportaciones. Datos ficticios de ejemplo.",
            "<a class='b p' href=/cliente>Nuevo cliente</a>", tbl(withActs(u, "Empresa", "País de origen", "Identificación fiscal", "Contacto", "Exportaciones"), rows, Set.of("Exportaciones")) + note(u));
    }
    static String cliForm(Usuario u, Map<String, String> q, Map<String, String> f, boolean post) {
        int id = toInt((post ? f : q).getOrDefault("id", "0")); Cliente c = id == 0 ? null : cli(id);
        if (id != 0 && (c == null || !u.admin())) return "REDIRECT:/clientes" + flash("No puede editar ese registro.", true);
        Map<String, String> v = new HashMap<>(f); List<String> e = new ArrayList<>();
        if (!post) { v.put("em", c == null ? "" : c.empresa); v.put("pa", c == null ? "0" : "" + c.idPais); v.put("nif", c == null ? "" : c.nif);
            v.put("co", c == null ? "" : c.contacto); v.put("ma", c == null ? "" : c.correo); v.put("te", c == null || c.telefono == null ? "" : c.telefono); }
        else {
            String n = f.getOrDefault("em", "").trim(), nif = f.getOrDefault("nif", "").toUpperCase().replaceAll("[\\s.\\-]", ""), cnt = f.getOrDefault("co", "").trim(),
                mail = f.getOrDefault("ma", "").trim(), tl = f.getOrDefault("te", "").trim();
            Pais pa = pais(toInt(f.getOrDefault("pa", "0")));
            if (n.isEmpty()) e.add("El nombre de la empresa es obligatorio."); else if (n.length() > 120) e.add("El nombre de la empresa no puede superar 120 caracteres.");
            if (pa == null) e.add("Seleccione el país de origen.");
            if (nif.isEmpty()) e.add("La identificación fiscal (VAT) es obligatoria.");
            else if (pa != null) {
                String[] vt = VAT.get(pa.iso);
                if (!nif.matches(vt[0])) e.add("La identificación fiscal no tiene un formato válido para " + pa.nombre + " (ejemplo: " + vt[1] + ").");
                else if (db.clientes.stream().anyMatch(x -> (c == null || x.id != c.id) && x.nif.equals(nif))) e.add("Ya existe un cliente con esa identificación fiscal.");
            }
            if (cnt.isEmpty()) e.add("El nombre de contacto es obligatorio."); else if (cnt.length() > 100) e.add("El nombre de contacto no puede superar 100 caracteres.");
            if (mail.isEmpty()) e.add("El correo de contacto es obligatorio.");
            else if (!mail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}") || mail.length() > 120) e.add("El correo de contacto no tiene un formato válido.");
            if (!tl.isEmpty() && !tl.matches("\\+?[\\d\\s().-]{6,30}")) e.add("El teléfono solo puede contener números, espacios y los signos + ( ) . -");
            if (e.isEmpty()) {
                Cliente t = c == null ? new Cliente() : c;
                if (c == null) { t.id = db.seqCliente++; db.clientes.add(t); }
                t.empresa = n; t.idPais = pa.id; t.nif = nif; t.contacto = cnt; t.correo = mail; t.telefono = tl.isEmpty() ? null : tl;
                return "REDIRECT:/clientes" + flash(c == null ? "Cliente registrado." : "Cliente actualizado.", false);
            }
        }
        String form = "<form method=post action=/cliente class=card style='max-width:520px'>" + errs(e) + (c != null ? "<input type=hidden name=id value=" + c.id + ">" : "")
            + "<label>Nombre de la empresa</label><input name=em value='" + h(v.getOrDefault("em", "")) + "'>"
            + "<label>País de origen</label><select name=pa id=pa onchange=hint()>" + opts(paisItems(), v.getOrDefault("pa", "0")) + "</select>"
            + "<label>Identificación fiscal (VAT)</label><input name=nif value='" + h(v.getOrDefault("nif", "")) + "'><div class=hint id=ht></div>"
            + "<label>Persona de contacto</label><input name=co value='" + h(v.getOrDefault("co", "")) + "'>"
            + "<label>Correo de contacto</label><input name=ma value='" + h(v.getOrDefault("ma", "")) + "'>"
            + "<label>Teléfono (opcional)</label><input name=te value='" + h(v.getOrDefault("te", "")) + "'><br>"
            + "<a class=b href=/clientes>Cancelar</a> <button class=p>Guardar</button></form>"
            + "<script>function hint(){ht.textContent=pa.selectedOptions[0].dataset.h||'Elija un país para ver el formato.'}hint()</script>";
        return layout(u, "clientes", q, c == null ? "Nuevo cliente" : "Editar cliente", "", "", form);
    }

    // ======================= Países, cobertura, eliminar =======================
    static String paises(Usuario u, Map<String, String> q) {
        List<String[]> rows = db.paises.stream().sorted(Comparator.comparing((Pais p) -> p.nombre)).map(p -> new String[]{p.iso, p.nombre, p.region, p.moneda,
            "" + db.clientes.stream().filter(c -> c.idPais == p.id).count(), "" + db.exp.stream().filter(x -> x.idPais == p.id).count()}).collect(Collectors.toList());
        return layout(u, "paises", q, "Países de destino", "Lista de ejemplo cargada localmente. La consulta con REST Countries queda para un sprint posterior.", "",
            tbl(new String[]{"Código ISO", "País", "Región", "Moneda", "Clientes", "Exportaciones"}, rows, Set.of("Clientes", "Exportaciones")));
    }
    static String cobertura(Usuario u, Map<String, String> q) {
        List<String[]> rows = Arrays.stream(COV).map(r -> new String[]{r[0], r[1], "<span class='tag " + r[2] + "'>" + COVL.get(r[2]) + "</span>"}).collect(Collectors.toList());
        return layout(u, "cobertura", q, "Cobertura de requisitos", "Estado de los requisitos funcionales del documento en este prototipo.", "",
            tbl(new String[]{"ID", "Requisito", "Estado"}, rows, Set.of())
            + "<p class=mut>Las integraciones con VIES VAT, REST Countries y ExchangeRate-API dependen de servicios externos y están fuera del alcance del Sprint 2.<br>"
            + "Los datos viven en memoria del servidor; no hay conexión a MySQL.</p>");
    }
    static String eliminar(Usuario u, Map<String, String> f) {
        if (!u.admin()) return "REDIRECT:/resumen" + flash("Solo el administrador puede eliminar registros.", true);
        String k = f.getOrDefault("k", ""); int id = toInt(f.getOrDefault("id", "0"));
        switch (k) {
            case "exp": { Exportacion x = exp(id); if (x != null) db.exp.remove(x); return "REDIRECT:/exportaciones" + flash("Registro eliminado.", false); }
            case "prod": {
                Producto p = prod(id); long n = db.exp.stream().filter(x -> x.idProducto == id).count();
                if (p != null && n > 0) return "REDIRECT:/productos" + flash("No se puede eliminar «" + pn(p) + "»: tiene " + n + " exportación(es) asociada(s).", true);
                if (p != null) db.productos.remove(p); return "REDIRECT:/productos" + flash("Registro eliminado.", false);
            }
            case "cli": {
                Cliente c = cli(id); long n = db.exp.stream().filter(x -> x.idCliente == id).count();
                if (c != null && n > 0) return "REDIRECT:/clientes" + flash("No se puede eliminar «" + c.empresa + "»: tiene " + n + " exportación(es) asociada(s).", true);
                if (c != null) db.clientes.remove(c); return "REDIRECT:/clientes" + flash("Registro eliminado.", false);
            }
            default: return "REDIRECT:/resumen";
        }
    }
}
