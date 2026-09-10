package com.thiaguinho.controlemotospiu;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "controle_motos_piu.db";
    private static final int DB_VERSION = 3;
    private final SimpleDateFormat isoDay = new SimpleDateFormat("yyyy-MM-dd", new Locale("pt", "BR"));
    private final SimpleDateFormat brDay = new SimpleDateFormat("dd/MM/yyyy", new Locale("pt", "BR"));

    public DatabaseHelper(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createBaseTables(db);
    }

    private void createBaseTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS services (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "plate TEXT NOT NULL," +
                "km INTEGER DEFAULT 0," +
                "service_date TEXT NOT NULL," +
                "service_text TEXT NOT NULL," +
                "labor_value REAL DEFAULT 0," +
                "notes TEXT DEFAULT ''," +
                "created_at TEXT NOT NULL," +
                "updated_at TEXT NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'OPEN')");
        db.execSQL("CREATE TABLE IF NOT EXISTS service_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "service_id INTEGER NOT NULL," +
                "description TEXT NOT NULL," +
                "value REAL DEFAULT 0," +
                "FOREIGN KEY(service_id) REFERENCES services(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS parts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "service_id INTEGER NOT NULL," +
                "description TEXT NOT NULL," +
                "quantity REAL DEFAULT 1," +
                "unit_value REAL DEFAULT 0," +
                "total_value REAL DEFAULT 0," +
                "FOREIGN KEY(service_id) REFERENCES services(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_plate ON services(plate)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_date ON services(service_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_status ON services(status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_items_service ON service_items(service_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_items_desc ON service_items(description)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parts_service ON parts(service_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parts_desc ON parts(description)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS service_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "service_id INTEGER NOT NULL," +
                    "description TEXT NOT NULL," +
                    "value REAL DEFAULT 0," +
                    "FOREIGN KEY(service_id) REFERENCES services(id) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_items_service ON service_items(service_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_items_desc ON service_items(description)");
            db.execSQL("INSERT INTO service_items(service_id,description,value) " +
                    "SELECT id,service_text,labor_value FROM services " +
                    "WHERE trim(service_text)<>'' AND NOT EXISTS(SELECT 1 FROM service_items i WHERE i.service_id=services.id)");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE services ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN'");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_services_status ON services(status)");
        }
    }

    public long saveService(Long id, String plate, long km, String date,
                            JSONArray serviceItems, String notes, JSONArray parts, String now) throws Exception {
        String normalizedPlate = normalizePlate(plate);
        if (normalizedPlate.length() != 7) throw new Exception("Informe uma placa válida com 7 caracteres.");
        if (date == null || date.trim().isEmpty()) throw new Exception("Informe a data do atendimento.");

        JSONArray cleanServices = new JSONArray();
        double serviceTotal = 0;
        List<String> serviceNames = new ArrayList<>();
        if (serviceItems != null) {
            for (int i = 0; i < serviceItems.length(); i++) {
                JSONObject item = serviceItems.getJSONObject(i);
                String desc = item.optString("description", "").trim();
                if (desc.isEmpty()) continue;
                double value = item.optDouble("value", 0);
                if (value < 0) throw new Exception("O valor de um serviço não pode ser negativo.");
                JSONObject clean = new JSONObject();
                clean.put("description", desc);
                clean.put("value", round2(value));
                cleanServices.put(clean);
                serviceTotal += value;
                serviceNames.add(desc);
            }
        }
        if (cleanServices.length() == 0) throw new Exception("Adicione pelo menos um serviço realizado.");

        String summaryText = joinServiceNames(serviceNames);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("plate", normalizedPlate);
            values.put("km", Math.max(0, km));
            values.put("service_date", date);
            values.put("service_text", summaryText);
            values.put("labor_value", round2(serviceTotal));
            values.put("notes", notes == null ? "" : notes.trim());
            values.put("updated_at", now);
            long serviceId;
            if (id == null) {
                values.put("created_at", now);
                serviceId = db.insertOrThrow("services", null, values);
            } else {
                int changed = db.update("services", values, "id=?", new String[]{String.valueOf(id)});
                if (changed == 0) throw new Exception("Atendimento não encontrado.");
                serviceId = id;
                db.delete("service_items", "service_id=?", new String[]{String.valueOf(serviceId)});
                db.delete("parts", "service_id=?", new String[]{String.valueOf(serviceId)});
            }

            for (int i = 0; i < cleanServices.length(); i++) {
                JSONObject item = cleanServices.getJSONObject(i);
                ContentValues iv = new ContentValues();
                iv.put("service_id", serviceId);
                iv.put("description", item.getString("description"));
                iv.put("value", round2(item.optDouble("value", 0)));
                db.insertOrThrow("service_items", null, iv);
            }

            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    String desc = p.optString("description", "").trim();
                    if (desc.isEmpty()) continue;
                    double qty = p.optDouble("quantity", 1);
                    double unit = p.optDouble("unitValue", 0);
                    if (qty <= 0) throw new Exception("A quantidade da peça deve ser maior que zero.");
                    if (unit < 0) throw new Exception("O valor da peça não pode ser negativo.");
                    ContentValues pv = new ContentValues();
                    pv.put("service_id", serviceId);
                    pv.put("description", desc);
                    pv.put("quantity", qty);
                    pv.put("unit_value", round2(unit));
                    pv.put("total_value", round2(qty * unit));
                    db.insertOrThrow("parts", null, pv);
                }
            }
            db.setTransactionSuccessful();
            return serviceId;
        } finally {
            db.endTransaction();
        }
    }

    public Cursor services(String search, String startDate, String endDate) {
        return services(search, startDate, endDate, "ALL");
    }

    public Cursor services(String search, String startDate, String endDate, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.*, " +
                "COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total, " +
                "COALESCE((SELECT COUNT(*) FROM parts p WHERE p.service_id=s.id),0) parts_count, " +
                "COALESCE((SELECT COUNT(*) FROM service_items i WHERE i.service_id=s.id),0) service_items_count " +
                "FROM services s WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (startDate != null && !startDate.isEmpty()) { sql.append(" AND s.service_date>=?"); args.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) { sql.append(" AND s.service_date<=?"); args.add(endDate); }
        String st = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if ("OPEN".equals(st) || "CLOSED".equals(st)) { sql.append(" AND s.status=?"); args.add(st); }
        String q = search == null ? "" : search.trim();
        if (!q.isEmpty()) {
            sql.append(" AND (s.plate LIKE ? OR s.plate LIKE ? OR s.service_text LIKE ? OR s.notes LIKE ? " +
                    "OR EXISTS(SELECT 1 FROM service_items i WHERE i.service_id=s.id AND i.description LIKE ?) " +
                    "OR EXISTS(SELECT 1 FROM parts p WHERE p.service_id=s.id AND p.description LIKE ?))");
            String like = "%" + q + "%";
            String plateLike = "%" + normalizePlate(q) + "%";
            args.add(like); args.add(plateLike); args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY s.service_date DESC, s.id DESC");
        return getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
    }

    public Cursor service(long id) {
        return getReadableDatabase().rawQuery(
                "SELECT s.*, " +
                "COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total, " +
                "COALESCE((SELECT COUNT(*) FROM parts p WHERE p.service_id=s.id),0) parts_count, " +
                "COALESCE((SELECT COUNT(*) FROM service_items i WHERE i.service_id=s.id),0) service_items_count " +
                "FROM services s WHERE s.id=?", new String[]{String.valueOf(id)});
    }

    public Cursor serviceItems(long serviceId) {
        return getReadableDatabase().rawQuery("SELECT * FROM service_items WHERE service_id=? ORDER BY id",
                new String[]{String.valueOf(serviceId)});
    }

    public Cursor parts(long serviceId) {
        return getReadableDatabase().rawQuery("SELECT * FROM parts WHERE service_id=? ORDER BY id",
                new String[]{String.valueOf(serviceId)});
    }

    public int deleteService(long id) {
        return getWritableDatabase().delete("services", "id=?", new String[]{String.valueOf(id)});
    }

    public boolean setServiceStatus(long id, String status) {
        String st = "CLOSED".equalsIgnoreCase(status) ? "CLOSED" : "OPEN";
        ContentValues v = new ContentValues();
        v.put("status", st);
        v.put("updated_at", nowIso());
        return getWritableDatabase().update("services", v, "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    public int deleteServices(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int total = 0;
            for (Long id : ids) total += db.delete("services", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            return total;
        } finally { db.endTransaction(); }
    }

    public Summary summary(String startDate, String endDate) {
        return summary(startDate, endDate, "ALL");
    }

    public Summary summary(String startDate, String endDate, String status) {
        Cursor c = summaryCursor(startDate, endDate, status);
        try {
            if (!c.moveToFirst()) return new Summary();
            Summary s = new Summary();
            s.count = c.getInt(c.getColumnIndexOrThrow("service_count"));
            s.openCount = c.getInt(c.getColumnIndexOrThrow("open_count"));
            s.closedCount = c.getInt(c.getColumnIndexOrThrow("closed_count"));
            s.labor = c.getDouble(c.getColumnIndexOrThrow("service_total"));
            s.parts = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
            s.total = s.labor + s.parts;
            return s;
        } finally { c.close(); }
    }

    public Summary summaryForIds(Set<Long> ids) {
        Summary out = new Summary();
        if (ids == null || ids.isEmpty()) return out;
        for (Long id : ids) {
            Cursor c = service(id);
            try {
                if (!c.moveToFirst()) continue;
                out.count++;
                String st = c.getString(c.getColumnIndexOrThrow("status"));
                if ("CLOSED".equalsIgnoreCase(st)) out.closedCount++; else out.openCount++;
                out.labor += c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                out.parts += c.getDouble(c.getColumnIndexOrThrow("parts_total"));
            } finally { c.close(); }
        }
        out.total = out.labor + out.parts;
        return out;
    }

    private Cursor summaryCursor(String startDate, String endDate, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) service_count, " +
                "COALESCE(SUM(CASE WHEN s.status='OPEN' THEN 1 ELSE 0 END),0) open_count, " +
                "COALESCE(SUM(CASE WHEN s.status='CLOSED' THEN 1 ELSE 0 END),0) closed_count, " +
                "COALESCE(SUM(s.labor_value),0) service_total, " +
                "COALESCE(SUM((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id)),0) parts_total " +
                "FROM services s WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (startDate != null && !startDate.isEmpty()) { sql.append(" AND s.service_date>=?"); args.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) { sql.append(" AND s.service_date<=?"); args.add(endDate); }
        String st = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if ("OPEN".equals(st) || "CLOSED".equals(st)) { sql.append(" AND s.status=?"); args.add(st); }
        return getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
    }

    public JSONObject exportAll() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "controle-motos-piu");
        root.put("version", 3);
        JSONArray services = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT * FROM services ORDER BY id", null);
        try {
            while (c.moveToNext()) {
                JSONObject s = new JSONObject();
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                s.put("id", id);
                s.put("plate", c.getString(c.getColumnIndexOrThrow("plate")));
                s.put("km", c.getLong(c.getColumnIndexOrThrow("km")));
                s.put("serviceDate", c.getString(c.getColumnIndexOrThrow("service_date")));
                s.put("serviceText", c.getString(c.getColumnIndexOrThrow("service_text")));
                s.put("laborValue", c.getDouble(c.getColumnIndexOrThrow("labor_value")));
                s.put("notes", c.getString(c.getColumnIndexOrThrow("notes")));
                s.put("createdAt", c.getString(c.getColumnIndexOrThrow("created_at")));
                s.put("updatedAt", c.getString(c.getColumnIndexOrThrow("updated_at")));
                s.put("status", c.getString(c.getColumnIndexOrThrow("status")));

                JSONArray itemArr = new JSONArray();
                Cursor items = serviceItems(id);
                try {
                    while (items.moveToNext()) {
                        JSONObject item = new JSONObject();
                        item.put("description", items.getString(items.getColumnIndexOrThrow("description")));
                        item.put("value", items.getDouble(items.getColumnIndexOrThrow("value")));
                        itemArr.put(item);
                    }
                } finally { items.close(); }
                s.put("serviceItems", itemArr);

                JSONArray pArr = new JSONArray();
                Cursor p = parts(id);
                try {
                    while (p.moveToNext()) {
                        JSONObject part = new JSONObject();
                        part.put("description", p.getString(p.getColumnIndexOrThrow("description")));
                        part.put("quantity", p.getDouble(p.getColumnIndexOrThrow("quantity")));
                        part.put("unitValue", p.getDouble(p.getColumnIndexOrThrow("unit_value")));
                        pArr.put(part);
                    }
                } finally { p.close(); }
                s.put("parts", pArr);
                services.put(s);
            }
        } finally { c.close(); }
        root.put("services", services);
        return root;
    }

    public void importAll(JSONObject root) throws Exception {
        if (!"controle-motos-piu".equals(root.optString("format")))
            throw new Exception("Este arquivo não é um backup do Controle de Motos do Piu.");
        JSONArray arr = root.optJSONArray("services");
        if (arr == null) throw new Exception("Backup inválido: atendimentos não encontrados.");

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("parts", null, null);
            db.delete("service_items", null, null);
            db.delete("services", null, null);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                JSONArray items = s.optJSONArray("serviceItems");
                if (items == null || items.length() == 0) {
                    items = new JSONArray();
                    String legacyDesc = s.optString("serviceText", "").trim();
                    if (!legacyDesc.isEmpty()) {
                        JSONObject legacy = new JSONObject();
                        legacy.put("description", legacyDesc);
                        legacy.put("value", s.optDouble("laborValue", 0));
                        items.put(legacy);
                    }
                }
                if (items.length() == 0) continue;

                double totalServices = 0;
                List<String> names = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    String desc = item.optString("description", "").trim();
                    if (desc.isEmpty()) continue;
                    names.add(desc);
                    totalServices += Math.max(0, item.optDouble("value", 0));
                }
                if (names.isEmpty()) continue;

                ContentValues v = new ContentValues();
                v.put("plate", normalizePlate(s.optString("plate")));
                v.put("km", s.optLong("km", 0));
                v.put("service_date", s.optString("serviceDate"));
                v.put("service_text", joinServiceNames(names));
                v.put("labor_value", round2(totalServices));
                v.put("notes", s.optString("notes", ""));
                v.put("created_at", s.optString("createdAt", nowIso()));
                v.put("updated_at", s.optString("updatedAt", nowIso()));
                v.put("status", "CLOSED".equalsIgnoreCase(s.optString("status")) ? "CLOSED" : "OPEN");
                long newId = db.insertOrThrow("services", null, v);

                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    String desc = item.optString("description", "").trim();
                    if (desc.isEmpty()) continue;
                    ContentValues iv = new ContentValues();
                    iv.put("service_id", newId);
                    iv.put("description", desc);
                    iv.put("value", round2(Math.max(0, item.optDouble("value", 0))));
                    db.insertOrThrow("service_items", null, iv);
                }

                JSONArray parts = s.optJSONArray("parts");
                if (parts != null) {
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject p = parts.getJSONObject(j);
                        String desc = p.optString("description", "").trim();
                        if (desc.isEmpty()) continue;
                        double qty = p.optDouble("quantity", 1);
                        double unit = p.optDouble("unitValue", 0);
                        ContentValues pv = new ContentValues();
                        pv.put("service_id", newId);
                        pv.put("description", desc);
                        pv.put("quantity", qty <= 0 ? 1 : qty);
                        pv.put("unit_value", round2(Math.max(0, unit)));
                        pv.put("total_value", round2((qty <= 0 ? 1 : qty) * Math.max(0, unit)));
                        db.insertOrThrow("parts", null, pv);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public String exportCsv(String startDate, String endDate) {
        return exportCsv(startDate, endDate, "ALL", null);
    }

    public String exportCsv(String startDate, String endDate, String status, Set<Long> ids) {
        StringBuilder out = new StringBuilder();
        out.append("Status;Data;Placa;KM;Servicos;Pecas;Total_servicos;Total_pecas;Total;Observacao\n");
        Cursor c = services("", startDate, endDate, status);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                if (ids != null && !ids.isEmpty() && !ids.contains(id)) continue;
                double servicesTotal = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                double partsTotal = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                String st = "CLOSED".equalsIgnoreCase(c.getString(c.getColumnIndexOrThrow("status"))) ? "FECHADA" : "ABERTA";
                out.append(csv(st)).append(';')
                   .append(csv(br(c.getString(c.getColumnIndexOrThrow("service_date"))))).append(';')
                   .append(csv(displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))))).append(';')
                   .append(c.getLong(c.getColumnIndexOrThrow("km"))).append(';')
                   .append(csv(servicesInline(id))).append(';')
                   .append(csv(partsInline(id))).append(';')
                   .append(String.format(Locale.US, "%.2f", servicesTotal)).append(';')
                   .append(String.format(Locale.US, "%.2f", partsTotal)).append(';')
                   .append(String.format(Locale.US, "%.2f", servicesTotal + partsTotal)).append(';')
                   .append(csv(c.getString(c.getColumnIndexOrThrow("notes")))).append('\n');
            }
        } finally { c.close(); }
        return out.toString();
    }

    public String exportExcelHtml(String startDate, String endDate, String status, Set<Long> ids) {
        Summary summary = (ids == null || ids.isEmpty()) ? summary(startDate, endDate, status) : summaryForIds(ids);
        String statusLabel = "OPEN".equalsIgnoreCase(status) ? "Somente abertas" :
                ("CLOSED".equalsIgnoreCase(status) ? "Somente fechadas" : "Abertas e fechadas");
        String periodLabel = (startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty()) ? "Todos os períodos" :
                (startDate == null || startDate.isEmpty() ? "Até " + br(endDate) :
                        (endDate == null || endDate.isEmpty() ? "A partir de " + br(startDate) : br(startDate) + " a " + br(endDate)));

        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><style>")
           .append("body{font-family:Arial,sans-serif;font-size:12pt;color:#172033;background:#fff;margin:18px}table{border-collapse:collapse;width:100%}")
           .append(".title{font-size:20pt;font-weight:700;color:#12233f;margin-bottom:3px}.sub{font-size:10.5pt;color:#66758a;margin-bottom:14px}")
           .append(".summary{margin:0 0 18px 0}.summary td{padding:8px 12px;border:1px solid #dce3ec;background:#f8fafc}.summary .v{font-weight:700;font-size:13pt;color:#12233f}")
           .append("th{background:#12233f;color:#fff;font-weight:700;padding:11px 9px;border:1px solid #aebccc;text-align:left;vertical-align:middle}")
           .append("td{padding:10px 9px;border:1px solid #dce3ec;vertical-align:top;line-height:1.35}tr:nth-child(even){background:#f7f9fc}")
           .append(".money{text-align:right;white-space:nowrap;mso-number-format:'0.00'}.km{text-align:right;white-space:nowrap}.plate{font-weight:700;white-space:nowrap}.status{font-weight:700;text-align:center;white-space:nowrap}.wrap{white-space:normal;min-width:240px}")
           .append("</style></head><body>")
           .append("<div class='title'>Controle de Motos • Piu</div>")
           .append("<div class='sub'>").append(xml(periodLabel)).append(" • ").append(xml(statusLabel))
           .append(ids != null && !ids.isEmpty() ? " • " + ids.size() + " OS selecionada(s)" : "").append("</div>")
           .append("<table class='summary'><tr><td>Atendimentos<br><span class='v'>").append(summary.count).append("</span></td>")
           .append("<td>Abertas<br><span class='v'>").append(summary.openCount).append("</span></td>")
           .append("<td>Fechadas<br><span class='v'>").append(summary.closedCount).append("</span></td>")
           .append("<td>Serviços<br><span class='v'>").append(xml(money(summary.labor))).append("</span></td>")
           .append("<td>Peças<br><span class='v'>").append(xml(money(summary.parts))).append("</span></td>")
           .append("<td>Total<br><span class='v'>").append(xml(money(summary.total))).append("</span></td></tr></table>")
           .append("<table><colgroup><col style='width:90px'><col style='width:100px'><col style='width:105px'><col style='width:90px'><col style='width:320px'><col style='width:320px'><col style='width:110px'><col style='width:110px'><col style='width:115px'><col style='width:320px'></colgroup>")
           .append("<tr><th>Status</th><th>Data</th><th>Placa</th><th>KM</th><th>Serviços</th><th>Peças</th><th>Serviços R$</th><th>Peças R$</th><th>Total R$</th><th>Observação</th></tr>");
        Cursor c = services("", startDate, endDate, status);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                if (ids != null && !ids.isEmpty() && !ids.contains(id)) continue;
                double sv = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                double pv = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                String st = "CLOSED".equalsIgnoreCase(c.getString(c.getColumnIndexOrThrow("status"))) ? "FECHADA" : "ABERTA";
                String servicesHtml = xml(servicesInline(id)).replace(" | ", "<br>");
                String partsHtml = xml(partsInline(id)).replace(" | ", "<br>");
                out.append("<tr><td class='status'>").append(xml(st)).append("</td><td>").append(xml(br(c.getString(c.getColumnIndexOrThrow("service_date"))))).append("</td><td class='plate'>")
                   .append(xml(displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))))).append("</td><td class='km'>").append(formatInt(c.getLong(c.getColumnIndexOrThrow("km")))).append("</td><td class='wrap'>")
                   .append(servicesHtml).append("</td><td class='wrap'>").append(partsHtml).append("</td><td class='money'>").append(String.format(new Locale("pt", "BR"), "%.2f", sv)).append("</td><td class='money'>")
                   .append(String.format(new Locale("pt", "BR"), "%.2f", pv)).append("</td><td class='money'><b>").append(String.format(new Locale("pt", "BR"), "%.2f", sv+pv)).append("</b></td><td class='wrap'>")
                   .append(xml(c.getString(c.getColumnIndexOrThrow("notes")))).append("</td></tr>");
            }
        } finally { c.close(); }
        out.append("</table></body></html>");
        return out.toString();
    }

    public String servicesInline(long serviceId) {
        StringBuilder sb = new StringBuilder();
        Cursor c = serviceItems(serviceId);
        try {
            while (c.moveToNext()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(c.getString(c.getColumnIndexOrThrow("description")))
                  .append(" — ").append(money(c.getDouble(c.getColumnIndexOrThrow("value"))));
            }
        } finally { c.close(); }
        return sb.toString();
    }

    public String partsInline(long serviceId) {
        StringBuilder sb = new StringBuilder();
        Cursor p = parts(serviceId);
        try {
            while (p.moveToNext()) {
                if (sb.length() > 0) sb.append(" | ");
                double qty = p.getDouble(p.getColumnIndexOrThrow("quantity"));
                double unit = p.getDouble(p.getColumnIndexOrThrow("unit_value"));
                sb.append(p.getString(p.getColumnIndexOrThrow("description")))
                  .append(" x").append(formatQty(qty))
                  .append(" — ").append(money(qty * unit));
            }
        } finally { p.close(); }
        return sb.toString();
    }

    public String answerLocalAssistant(String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.trim();
        if (question.isEmpty()) return "Digite o que você quer consultar nos atendimentos.";
        String n = normalizeText(question);
        String plate = extractPlate(question);
        DateRange range = parseRange(n, question);

        if (plate != null) {
            Cursor c = services(plate, range.start, range.end);
            try {
                if (!c.moveToFirst()) return "Não encontrei atendimento para a placa " + displayPlate(plate) + range.labelSuffix() + ".";
                if (containsAny(n, "ultimo km", "quilometragem", " km", "km ")) {
                    return "Último KM registrado de " + displayPlate(plate) + ": " +
                            formatInt(c.getLong(c.getColumnIndexOrThrow("km"))) + " km, em " +
                            br(c.getString(c.getColumnIndexOrThrow("service_date"))) + ".";
                }
                if (containsAny(n, "quanto", "total", "gasto", "valor", "fatur")) {
                    double servicesTotal = 0, partsTotal = 0; int count = 0;
                    do {
                        servicesTotal += c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                        partsTotal += c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                        count++;
                    } while (c.moveToNext());
                    return displayPlate(plate) + range.labelSuffix() + ": " + count + " atendimento(s), serviços " +
                            money(servicesTotal) + ", peças " + money(partsTotal) + ", total " + money(servicesTotal + partsTotal) + ".";
                }
                StringBuilder sb = new StringBuilder("Histórico de ").append(displayPlate(plate)).append(range.labelSuffix()).append(":");
                int shown = 0;
                do {
                    long id = c.getLong(c.getColumnIndexOrThrow("id"));
                    double st = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                    double pt = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                    sb.append("\n\n").append(br(c.getString(c.getColumnIndexOrThrow("service_date"))))
                      .append(" • ").append(formatInt(c.getLong(c.getColumnIndexOrThrow("km")))).append(" km")
                      .append("\nServiços: ").append(servicesInline(id));
                    String parts = partsInline(id);
                    if (!parts.isEmpty()) sb.append("\nPeças: ").append(parts);
                    String notes = c.getString(c.getColumnIndexOrThrow("notes"));
                    if (notes != null && !notes.trim().isEmpty()) sb.append("\nObs.: ").append(notes.trim());
                    sb.append("\nTotal: ").append(money(st + pt));
                    shown++;
                } while (c.moveToNext() && shown < 10);
                return sb.toString();
            } finally { c.close(); }
        }

        if (containsAny(n, "pecas mais", "peca mais", "mais usei", "mais usada", "mais usadas")) {
            String sql = "SELECT p.description, SUM(p.quantity) qtd, SUM(p.total_value) total FROM parts p " +
                    "JOIN services s ON s.id=p.service_id WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " GROUP BY lower(p.description) ORDER BY qtd DESC,total DESC LIMIT 12";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Ainda não há peças registradas" + range.labelSuffix() + ".";
                StringBuilder sb = new StringBuilder("Peças mais usadas").append(range.labelSuffix()).append(":");
                do sb.append("\n• ").append(c.getString(0)).append(" — ").append(formatQty(c.getDouble(1))).append(" un. — ").append(money(c.getDouble(2)));
                while (c.moveToNext());
                return sb.toString();
            } finally { c.close(); }
        }

        if (containsAny(n, "quais servicos", "servicos fiz", "servico mais", "servicos mais")) {
            String sql = "SELECT i.description, COUNT(*) qtd, SUM(i.value) total FROM service_items i " +
                    "JOIN services s ON s.id=i.service_id WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " GROUP BY lower(i.description) ORDER BY qtd DESC,total DESC LIMIT 15";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Ainda não há serviços registrados" + range.labelSuffix() + ".";
                StringBuilder sb = new StringBuilder("Serviços registrados").append(range.labelSuffix()).append(":");
                do sb.append("\n• ").append(c.getString(0)).append(" — ").append(c.getInt(1)).append(" vez(es) — ").append(money(c.getDouble(2)));
                while (c.moveToNext());
                return sb.toString();
            } finally { c.close(); }
        }

        if (containsAny(n, "maior atendimento", "maior valor", "mais caro")) {
            String sql = "SELECT s.*, COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total FROM services s WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " ORDER BY (s.labor_value + COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0)) DESC LIMIT 1";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Ainda não há atendimentos" + range.labelSuffix() + ".";
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                return "Maior atendimento" + range.labelSuffix() + ": " + displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))) +
                        ", em " + br(c.getString(c.getColumnIndexOrThrow("service_date"))) + ", serviços: " + servicesInline(id) +
                        ", total " + money(c.getDouble(c.getColumnIndexOrThrow("labor_value")) + c.getDouble(c.getColumnIndexOrThrow("parts_total"))) + ".";
            } finally { c.close(); }
        }

        if (containsAny(n, "quantas motos", "quais motos", "placas atendidas", "motos atendidas")) {
            String sql = "SELECT s.plate, COUNT(*) qtd, MAX(s.service_date) ultima FROM services s WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " GROUP BY s.plate ORDER BY ultima DESC";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Nenhuma moto encontrada" + range.labelSuffix() + ".";
                StringBuilder sb = new StringBuilder(); int count = 0;
                do {
                    count++;
                    if (count <= 20) sb.append("\n• ").append(displayPlate(c.getString(0))).append(" — ").append(c.getInt(1)).append(" atendimento(s) — último ").append(br(c.getString(2)));
                } while (c.moveToNext());
                return count + " moto(s) diferente(s)" + range.labelSuffix() + ":" + sb + (count > 20 ? "\n… e mais " + (count - 20) + "." : "");
            } finally { c.close(); }
        }

        if (containsAny(n, "quanto", "total", "fatur", "resumo", "quantos", "atendimentos", "movimento")) {
            Summary s = summary(range.start, range.end);
            return "Resumo" + range.labelSuffix() + ": " + s.count + " atendimento(s), serviços " + money(s.labor) +
                    ", peças " + money(s.parts) + ", total " + money(s.total) + ".";
        }

        // Busca livre em placa, serviços, peças e observações.
        List<String> terms = usefulTerms(n);
        if (terms.isEmpty()) terms.add(question.trim());
        Set<Long> ids = new LinkedHashSet<>();
        for (String term : terms) {
            Cursor c = services(term, range.start, range.end);
            try {
                while (c.moveToNext() && ids.size() < 12) ids.add(c.getLong(c.getColumnIndexOrThrow("id")));
            } finally { c.close(); }
        }
        if (ids.isEmpty()) return "Não encontrei informação lançada que corresponda a “" + question + "”.";

        StringBuilder sb = new StringBuilder("Encontrei ").append(ids.size()).append(" atendimento(s) relacionado(s):");
        int shown = 0;
        for (Long id : ids) {
            Cursor c = service(id);
            try {
                if (!c.moveToFirst()) continue;
                sb.append("\n\n• ").append(br(c.getString(c.getColumnIndexOrThrow("service_date"))))
                  .append(" — ").append(displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))))
                  .append("\nServiços: ").append(servicesInline(id));
                String pi = partsInline(id);
                if (!pi.isEmpty()) sb.append("\nPeças: ").append(pi);
                String notes = c.getString(c.getColumnIndexOrThrow("notes"));
                if (notes != null && !notes.trim().isEmpty()) sb.append("\nObs.: ").append(notes.trim());
                shown++;
                if (shown >= 8) break;
            } finally { c.close(); }
        }
        return sb.toString();
    }

    private DateRange parseRange(String normalized, String original) {
        DateRange r = new DateRange();
        Calendar now = Calendar.getInstance();
        if (normalized.contains("hoje")) {
            r.start = r.end = isoDay.format(now.getTime()); r.label = " de hoje"; return r;
        }
        if (normalized.contains("mes passado") || normalized.contains("ultimo mes")) {
            Calendar start = (Calendar) now.clone(); start.add(Calendar.MONTH, -1); start.set(Calendar.DAY_OF_MONTH, 1);
            Calendar end = (Calendar) start.clone(); end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH));
            r.start = isoDay.format(start.getTime()); r.end = isoDay.format(end.getTime()); r.label = " do mês passado"; return r;
        }
        if (normalized.contains("este ano") || normalized.contains("ano atual")) {
            Calendar start = (Calendar) now.clone(); start.set(Calendar.DAY_OF_YEAR, 1);
            r.start = isoDay.format(start.getTime()); r.end = isoDay.format(now.getTime()); r.label = " deste ano"; return r;
        }
        if (normalized.contains("este mes") || normalized.contains("nesse mes") || normalized.contains("no mes")) {
            Calendar start = (Calendar) now.clone(); start.set(Calendar.DAY_OF_MONTH, 1);
            r.start = isoDay.format(start.getTime()); r.end = isoDay.format(now.getTime()); r.label = " deste mês"; return r;
        }
        if (normalized.contains("esta semana") || normalized.contains("semana")) {
            Calendar start = (Calendar) now.clone(); start.setFirstDayOfWeek(Calendar.MONDAY);
            int day = start.get(Calendar.DAY_OF_WEEK); int diff = (day == Calendar.SUNDAY ? -6 : Calendar.MONDAY - day);
            start.add(Calendar.DAY_OF_MONTH, diff);
            r.start = isoDay.format(start.getTime()); r.end = isoDay.format(now.getTime()); r.label = " desta semana"; return r;
        }
        Matcher m = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*(?:a|ate|até|-)\\s*(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE).matcher(original);
        if (m.find()) {
            r.start = toIso(m.group(1)); r.end = toIso(m.group(2)); r.label = " de " + m.group(1) + " a " + m.group(2); return r;
        }
        Matcher one = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})").matcher(original);
        if (one.find()) { r.start = r.end = toIso(one.group(1)); r.label = " em " + one.group(1); }
        return r;
    }

    private List<String> usefulTerms(String normalized) {
        String[] stop = {"qual","quais","quanto","quantos","quem","onde","como","foi","foram","feito","feita","fizeram","mostre","mostrar","me","de","da","do","das","dos","em","no","na","nos","nas","um","uma","o","a","os","as","e","para","por","com","piu","moto","motos","atendimento","atendimentos","servico","servicos","peca","pecas","valor","valores"};
        Set<String> stopSet = new LinkedHashSet<>(); for (String s : stop) stopSet.add(s);
        List<String> out = new ArrayList<>();
        for (String t : normalized.split("\\s+")) if (t.length() >= 3 && !stopSet.contains(t)) out.add(t);
        return out;
    }

    private boolean containsAny(String text, String... terms) { for (String t : terms) if (text.contains(t)) return true; return false; }

    private String extractPlate(String text) {
        Matcher m = Pattern.compile("(?i)\\b([A-Z]{3}[- ]?[0-9][A-Z0-9][0-9]{2}|[A-Z]{3}[- ]?[0-9]{4})\\b").matcher(text);
        return m.find() ? normalizePlate(m.group(1)) : null;
    }

    public static String normalizePlate(String plate) { return plate == null ? "" : plate.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }

    public static String displayPlate(String plate) {
        String p = normalizePlate(plate);
        if (p.matches("[A-Z]{3}[0-9]{4}")) return p.substring(0, 3) + "-" + p.substring(3);
        return p;
    }

    private String normalizeText(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^a-z0-9/ -]", " ").replaceAll("\\s+", " ").trim();
    }

    private String br(String iso) { try { return brDay.format(isoDay.parse(iso)); } catch (Exception e) { return iso; } }
    private String toIso(String br) { try { return isoDay.format(brDay.parse(br)); } catch (Exception e) { return null; } }
    private String xml(String s) { return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private String csv(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"'; }
    private static String nowIso() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String money(double v) { return String.format(new Locale("pt", "BR"), "R$ %,.2f", v); }
    private String formatQty(double v) { return Math.abs(v - Math.rint(v)) < 0.000001 ? String.valueOf((long) Math.rint(v)) : String.format(new Locale("pt", "BR"), "%.2f", v); }
    private String formatInt(long v) { return String.format(new Locale("pt", "BR"), "%,d", v); }

    private static String joinServiceNames(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(name);
        }
        return sb.toString();
    }

    public static class Summary { public int count, openCount, closedCount; public double labor, parts, total; }
    private static class DateRange {
        String start, end, label = "";
        String labelSuffix() { return label == null ? "" : label; }
    }
}
