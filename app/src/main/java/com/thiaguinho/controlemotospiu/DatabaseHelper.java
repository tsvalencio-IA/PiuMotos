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
    private static final int DB_VERSION = 1;
    private final SimpleDateFormat isoDay = new SimpleDateFormat("yyyy-MM-dd", new Locale("pt", "BR"));
    private final SimpleDateFormat brDay = new SimpleDateFormat("dd/MM/yyyy", new Locale("pt", "BR"));

    public DatabaseHelper(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE services (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "plate TEXT NOT NULL," +
                "km INTEGER DEFAULT 0," +
                "service_date TEXT NOT NULL," +
                "service_text TEXT NOT NULL," +
                "labor_value REAL DEFAULT 0," +
                "notes TEXT DEFAULT ''," +
                "created_at TEXT NOT NULL," +
                "updated_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE parts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "service_id INTEGER NOT NULL," +
                "description TEXT NOT NULL," +
                "quantity REAL DEFAULT 1," +
                "unit_value REAL DEFAULT 0," +
                "total_value REAL DEFAULT 0," +
                "FOREIGN KEY(service_id) REFERENCES services(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_services_plate ON services(plate)");
        db.execSQL("CREATE INDEX idx_services_date ON services(service_date)");
        db.execSQL("CREATE INDEX idx_parts_service ON parts(service_id)");
        db.execSQL("CREATE INDEX idx_parts_desc ON parts(description)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public long saveService(Long id, String plate, long km, String date, String serviceText,
                            double laborValue, String notes, JSONArray parts, String now) throws Exception {
        String normalizedPlate = normalizePlate(plate);
        if (normalizedPlate.length() < 7) throw new Exception("Informe uma placa válida.");
        if (date == null || date.trim().isEmpty()) throw new Exception("Informe a data do atendimento.");
        if (serviceText == null || serviceText.trim().isEmpty()) throw new Exception("Informe o serviço realizado.");
        if (laborValue < 0) throw new Exception("O valor do serviço não pode ser negativo.");

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("plate", normalizedPlate);
            values.put("km", Math.max(0, km));
            values.put("service_date", date);
            values.put("service_text", serviceText.trim());
            values.put("labor_value", round2(laborValue));
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
                db.delete("parts", "service_id=?", new String[]{String.valueOf(serviceId)});
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
        StringBuilder sql = new StringBuilder(
                "SELECT s.*, COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total " +
                "FROM services s WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (startDate != null && !startDate.isEmpty()) { sql.append(" AND s.service_date>=?"); args.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) { sql.append(" AND s.service_date<=?"); args.add(endDate); }
        String q = search == null ? "" : search.trim();
        if (!q.isEmpty()) {
            sql.append(" AND (s.plate LIKE ? OR s.plate LIKE ? OR s.service_text LIKE ? OR s.notes LIKE ? OR EXISTS(SELECT 1 FROM parts p WHERE p.service_id=s.id AND p.description LIKE ?))");
            String like = "%" + q + "%";
            String plateLike = "%" + normalizePlate(q) + "%";
            args.add(like); args.add(plateLike); args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY s.service_date DESC, s.id DESC");
        return getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
    }

    public Cursor service(long id) {
        return getReadableDatabase().rawQuery(
                "SELECT s.*, COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total FROM services s WHERE id=?",
                new String[]{String.valueOf(id)});
    }

    public Cursor parts(long serviceId) {
        return getReadableDatabase().rawQuery("SELECT * FROM parts WHERE service_id=? ORDER BY id",
                new String[]{String.valueOf(serviceId)});
    }

    public int deleteService(long id) {
        return getWritableDatabase().delete("services", "id=?", new String[]{String.valueOf(id)});
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

    public int count(String startDate, String endDate) {
        Cursor c = summaryCursor(startDate, endDate);
        try { return c.moveToFirst() ? c.getInt(c.getColumnIndexOrThrow("service_count")) : 0; }
        finally { c.close(); }
    }

    public Summary summary(String startDate, String endDate) {
        Cursor c = summaryCursor(startDate, endDate);
        try {
            if (!c.moveToFirst()) return new Summary();
            Summary s = new Summary();
            s.count = c.getInt(c.getColumnIndexOrThrow("service_count"));
            s.labor = c.getDouble(c.getColumnIndexOrThrow("labor_total"));
            s.parts = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
            s.total = s.labor + s.parts;
            return s;
        } finally { c.close(); }
    }

    private Cursor summaryCursor(String startDate, String endDate) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) service_count, COALESCE(SUM(s.labor_value),0) labor_total, " +
                "COALESCE(SUM((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id)),0) parts_total " +
                "FROM services s WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (startDate != null && !startDate.isEmpty()) { sql.append(" AND s.service_date>=?"); args.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) { sql.append(" AND s.service_date<=?"); args.add(endDate); }
        return getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
    }

    public JSONObject exportAll() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "controle-motos-piu");
        root.put("version", 1);
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
        if (!"controle-motos-piu".equals(root.optString("format"))) throw new Exception("Este arquivo não é um backup do Controle de Motos do Piu.");
        JSONArray arr = root.optJSONArray("services");
        if (arr == null) throw new Exception("Backup inválido: atendimentos não encontrados.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("parts", null, null);
            db.delete("services", null, null);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                ContentValues v = new ContentValues();
                v.put("plate", normalizePlate(s.optString("plate")));
                v.put("km", s.optLong("km", 0));
                v.put("service_date", s.optString("serviceDate"));
                v.put("service_text", s.optString("serviceText"));
                v.put("labor_value", round2(s.optDouble("laborValue", 0)));
                v.put("notes", s.optString("notes", ""));
                v.put("created_at", s.optString("createdAt", nowIso()));
                v.put("updated_at", s.optString("updatedAt", nowIso()));
                long newId = db.insertOrThrow("services", null, v);
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
                        pv.put("quantity", qty);
                        pv.put("unit_value", round2(unit));
                        pv.put("total_value", round2(qty * unit));
                        db.insertOrThrow("parts", null, pv);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public String exportCsv(String startDate, String endDate) {
        StringBuilder out = new StringBuilder();
        out.append("Data;Placa;KM;Servico;Pecas;Mao_de_obra;Total;Observacao\n");
        Cursor c = services("", startDate, endDate);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(c.getColumnIndexOrThrow("id"));
                double labor = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                double partsTotal = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                out.append(csv(br(c.getString(c.getColumnIndexOrThrow("service_date"))))).append(';')
                   .append(csv(c.getString(c.getColumnIndexOrThrow("plate")))).append(';')
                   .append(c.getLong(c.getColumnIndexOrThrow("km"))).append(';')
                   .append(csv(c.getString(c.getColumnIndexOrThrow("service_text")))).append(';')
                   .append(csv(partsInline(id))).append(';')
                   .append(String.format(Locale.US, "%.2f", labor)).append(';')
                   .append(String.format(Locale.US, "%.2f", labor + partsTotal)).append(';')
                   .append(csv(c.getString(c.getColumnIndexOrThrow("notes")))).append('\n');
            }
        } finally { c.close(); }
        return out.toString();
    }

    public String partsInline(long serviceId) {
        StringBuilder sb = new StringBuilder();
        Cursor p = parts(serviceId);
        try {
            while (p.moveToNext()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(p.getString(p.getColumnIndexOrThrow("description")))
                  .append(" x")
                  .append(formatQty(p.getDouble(p.getColumnIndexOrThrow("quantity"))));
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
                    return "Último KM registrado de " + displayPlate(plate) + ": " + formatInt(c.getLong(c.getColumnIndexOrThrow("km"))) + " km, em " + br(c.getString(c.getColumnIndexOrThrow("service_date"))) + ".";
                }
                if (containsAny(n, "observacao", "observacoes")) {
                    StringBuilder sb = new StringBuilder(); int shown=0;
                    do {
                        String notes=c.getString(c.getColumnIndexOrThrow("notes"));
                        if(notes!=null&&!notes.trim().isEmpty()){
                            if(shown>0)sb.append("\n");
                            sb.append(br(c.getString(c.getColumnIndexOrThrow("service_date")))).append(" • ").append(notes.trim()); shown++;
                        }
                    } while(c.moveToNext());
                    return shown==0 ? "Não há observações registradas para "+displayPlate(plate)+range.labelSuffix()+"." : "Observações de "+displayPlate(plate)+range.labelSuffix()+":\n"+sb;
                }
                if (containsAny(n, "quanto", "total", "gasto", "valor", "fatur")) {
                    double total = 0, labor = 0, parts = 0; int count = 0;
                    do {
                        double l = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                        double p = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                        labor += l; parts += p; total += l + p; count++;
                    } while (c.moveToNext());
                    return displayPlate(plate) + range.labelSuffix() + ": " + count + " atendimento(s), mão de obra " + money(labor) + ", peças " + money(parts) + ", total " + money(total) + ".";
                }
                if (containsAny(n, "ultimo", "o que foi feito", "servico", "atendimento", "historico", "histórico", "peca", "peças", "pecas")) {
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    int limit = (n.contains("ultimo") && !n.contains("histor")) ? 1 : (containsAny(n,"completo","todos","tudo") ? 50 : 10);
                    do {
                        long id = c.getLong(c.getColumnIndexOrThrow("id"));
                        if (shown > 0) sb.append("\n");
                        sb.append(br(c.getString(c.getColumnIndexOrThrow("service_date")))).append(" • ")
                          .append(formatInt(c.getLong(c.getColumnIndexOrThrow("km")))).append(" km • ")
                          .append(c.getString(c.getColumnIndexOrThrow("service_text")));
                        String pi = partsInline(id);
                        if (!pi.isEmpty()) sb.append(" • Peças: ").append(pi);
                        String notes=c.getString(c.getColumnIndexOrThrow("notes"));
                        if(notes!=null&&!notes.trim().isEmpty()&&containsAny(n,"completo","tudo","observ")) sb.append(" • Obs.: ").append(notes.trim());
                        sb.append(" • Total: ").append(money(c.getDouble(c.getColumnIndexOrThrow("labor_value")) + c.getDouble(c.getColumnIndexOrThrow("parts_total"))));
                        shown++;
                    } while (shown < limit && c.moveToNext());
                    return (limit==1?"Último atendimento de ":"Histórico de ") + displayPlate(plate) + range.labelSuffix() + ":\n" + sb;
                }
            } finally { c.close(); }
        }

        if (containsAny(n, "peca mais", "peças mais", "pecas mais", "mais usada", "mais usadas")) {
            String sql = "SELECT p.description, SUM(p.quantity) qty, SUM(p.total_value) total FROM parts p JOIN services s ON s.id=p.service_id WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " GROUP BY lower(p.description) ORDER BY qty DESC, total DESC LIMIT 8";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Ainda não há peças registradas" + range.labelSuffix() + ".";
                StringBuilder sb = new StringBuilder("Peças mais usadas" + range.labelSuffix() + ":");
                do {
                    sb.append("\n• ").append(c.getString(0)).append(" — ").append(formatQty(c.getDouble(1))).append(" un. — ").append(money(c.getDouble(2)));
                } while (c.moveToNext());
                return sb.toString();
            } finally { c.close(); }
        }

        if (containsAny(n, "maior atendimento", "maior valor", "mais caro")) {
            String sql = "SELECT s.*, COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0) parts_total FROM services s WHERE 1=1";
            List<String> args = new ArrayList<>();
            if (range.start != null) { sql += " AND s.service_date>=?"; args.add(range.start); }
            if (range.end != null) { sql += " AND s.service_date<=?"; args.add(range.end); }
            sql += " ORDER BY (s.labor_value + parts_total) DESC LIMIT 1";
            Cursor c = getReadableDatabase().rawQuery(sql, args.toArray(new String[0]));
            try {
                if (!c.moveToFirst()) return "Ainda não há atendimentos" + range.labelSuffix() + ".";
                return "Maior atendimento" + range.labelSuffix() + ": " + displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))) + ", em " + br(c.getString(c.getColumnIndexOrThrow("service_date"))) + ", " + c.getString(c.getColumnIndexOrThrow("service_text")) + ", total " + money(c.getDouble(c.getColumnIndexOrThrow("labor_value")) + c.getDouble(c.getColumnIndexOrThrow("parts_total"))) + ".";
            } finally { c.close(); }
        }

        if (containsAny(n, "quais servicos", "servicos fiz", "servico mais", "servicos mais")) {
            String sql="SELECT s.service_text, COUNT(*) qtd, SUM(s.labor_value + COALESCE((SELECT SUM(p.total_value) FROM parts p WHERE p.service_id=s.id),0)) total FROM services s WHERE 1=1";
            List<String> args=new ArrayList<>();
            if(range.start!=null){sql+=" AND s.service_date>=?";args.add(range.start);} if(range.end!=null){sql+=" AND s.service_date<=?";args.add(range.end);}
            sql+=" GROUP BY lower(s.service_text) ORDER BY qtd DESC, total DESC LIMIT 12";
            Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]));
            try{if(!c.moveToFirst())return "Ainda não há serviços registrados"+range.labelSuffix()+".";StringBuilder sb=new StringBuilder("Serviços registrados"+range.labelSuffix()+":");do{sb.append("\n• ").append(c.getString(0)).append(" — ").append(c.getInt(1)).append(" atendimento(s) — ").append(money(c.getDouble(2)));}while(c.moveToNext());return sb.toString();}finally{c.close();}
        }

        if (containsAny(n, "quantas motos", "quais motos", "placas atendidas", "motos atendidas")) {
            String sql="SELECT s.plate, COUNT(*) qtd, MAX(s.service_date) ultima FROM services s WHERE 1=1";List<String> args=new ArrayList<>();
            if(range.start!=null){sql+=" AND s.service_date>=?";args.add(range.start);} if(range.end!=null){sql+=" AND s.service_date<=?";args.add(range.end);} sql+=" GROUP BY s.plate ORDER BY ultima DESC";
            Cursor c=getReadableDatabase().rawQuery(sql,args.toArray(new String[0]));
            try{if(!c.moveToFirst())return "Nenhuma moto encontrada"+range.labelSuffix()+".";StringBuilder sb=new StringBuilder();int count=0;do{count++;if(count<=20)sb.append("\n• ").append(displayPlate(c.getString(0))).append(" — ").append(c.getInt(1)).append(" atendimento(s) — último ").append(br(c.getString(2)));}while(c.moveToNext());return count+" moto(s) diferente(s)"+range.labelSuffix()+":"+sb+(count>20?"\n… e mais "+(count-20)+".":"");}finally{c.close();}
        }

        if (containsAny(n, "quanto", "total", "fatur", "resumo", "quantos", "atendimentos", "movimento")) {
            Summary s = summary(range.start, range.end);
            return "Resumo" + range.labelSuffix() + ": " + s.count + " atendimento(s), mão de obra " + money(s.labor) + ", peças " + money(s.parts) + ", total " + money(s.total) + ".";
        }

        // Busca livre em absolutamente todos os textos lançados.
        List<String> terms = usefulTerms(n);
        if (terms.isEmpty()) terms.add(question.trim());
        Set<Long> ids = new LinkedHashSet<>();
        for (String term : terms) {
            Cursor c = services(term, range.start, range.end);
            try { while (c.moveToNext() && ids.size() < 12) ids.add(c.getLong(c.getColumnIndexOrThrow("id"))); }
            finally { c.close(); }
        }
        if (ids.isEmpty()) return "Não encontrei informação lançada que corresponda a “" + question + "”.";
        StringBuilder sb = new StringBuilder("Encontrei ").append(ids.size()).append(" atendimento(s) relacionado(s):");
        int shown = 0;
        for (Long id : ids) {
            Cursor c = service(id);
            try {
                if (!c.moveToFirst()) continue;
                sb.append("\n• ").append(br(c.getString(c.getColumnIndexOrThrow("service_date"))))
                  .append(" — ").append(displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))))
                  .append(" — ").append(c.getString(c.getColumnIndexOrThrow("service_text")));
                String pi = partsInline(id);
                if (!pi.isEmpty()) sb.append(" — Peças: ").append(pi);
                String notes = c.getString(c.getColumnIndexOrThrow("notes"));
                if (notes != null && !notes.trim().isEmpty()) sb.append(" — Obs.: ").append(notes.trim());
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
            Calendar start=(Calendar)now.clone();start.add(Calendar.MONTH,-1);start.set(Calendar.DAY_OF_MONTH,1);
            Calendar end=(Calendar)start.clone();end.set(Calendar.DAY_OF_MONTH,end.getActualMaximum(Calendar.DAY_OF_MONTH));
            r.start=isoDay.format(start.getTime());r.end=isoDay.format(end.getTime());r.label=" do mês passado";return r;
        }
        if (normalized.contains("este ano") || normalized.contains("ano atual")) {
            Calendar start=(Calendar)now.clone();start.set(Calendar.DAY_OF_YEAR,1);r.start=isoDay.format(start.getTime());r.end=isoDay.format(now.getTime());r.label=" deste ano";return r;
        }
        if (normalized.contains("este mes") || normalized.contains("nesse mes") || normalized.contains("no mes")) {
            Calendar start = (Calendar) now.clone(); start.set(Calendar.DAY_OF_MONTH, 1);
            r.start = isoDay.format(start.getTime()); r.end = isoDay.format(now.getTime()); r.label = " deste mês"; return r;
        }
        if (normalized.contains("esta semana") || normalized.contains("semana")) {
            Calendar start = (Calendar) now.clone();
            start.setFirstDayOfWeek(Calendar.MONDAY);
            int day = start.get(Calendar.DAY_OF_WEEK);
            int diff = (day == Calendar.SUNDAY ? -6 : Calendar.MONDAY - day);
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
        Set<String> stopSet = new LinkedHashSet<>(); for(String s:stop) stopSet.add(s);
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
        if (p.matches("[A-Z]{3}[0-9]{4}")) return p.substring(0,3) + "-" + p.substring(3);
        return p;
    }
    private String normalizeText(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^a-z0-9/ -]", " ").replaceAll("\\s+", " ").trim();
    }
    private String br(String iso) { try { return brDay.format(isoDay.parse(iso)); } catch (Exception e) { return iso; } }
    private String toIso(String br) { try { return isoDay.format(brDay.parse(br)); } catch (Exception e) { return null; } }
    private String csv(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"'; }
    private static String nowIso() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String money(double v) { return String.format(new Locale("pt", "BR"), "R$ %,.2f", v); }
    private String formatQty(double v) { return Math.abs(v - Math.rint(v)) < 0.000001 ? String.valueOf((long)Math.rint(v)) : String.format(new Locale("pt", "BR"), "%.2f", v); }
    private String formatInt(long v) { return String.format(new Locale("pt", "BR"), "%,d", v); }

    public static class Summary { public int count; public double labor, parts, total; }
    private static class DateRange {
        String start, end, label = "";
        String labelSuffix() { return label == null ? "" : label; }
    }
}
