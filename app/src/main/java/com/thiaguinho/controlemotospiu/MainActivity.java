package com.thiaguinho.controlemotospiu;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_EXPORT_BACKUP = 6101;
    private static final int REQ_IMPORT_BACKUP = 6102;
    private static final int REQ_EXPORT_CSV = 6103;

    private DatabaseHelper db;
    private final Locale BR = new Locale("pt", "BR");
    private final SimpleDateFormat isoDay = new SimpleDateFormat("yyyy-MM-dd", BR);
    private final SimpleDateFormat brDay = new SimpleDateFormat("dd/MM/yyyy", BR);
    private final SimpleDateFormat nowFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    private String currentScreen = "home";
    private String searchText = "";
    private boolean selectionMode = false;
    private final Set<Long> selectedIds = new HashSet<>();
    private TextView selectionCount;
    private Button selectedReportButton, selectedDeleteButton;

    private Long editingId = null;
    private EditText plateInput, kmInput, dateInput, notesInput;
    private LinearLayout servicesContainer, partsContainer;
    private TextView servicesTotalText, partsTotalText, grandTotalText;
    private final List<ServiceRow> serviceRows = new ArrayList<>();
    private final List<PartRow> partRows = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C.dark);
        getWindow().setNavigationBarColor(C.dark);
        db = new DatabaseHelper(this);
        showHome();
    }

    @Override public void onBackPressed() {
        if (!"home".equals(currentScreen)) { showHome(); return; }
        if (selectionMode) { selectionMode = false; selectedIds.clear(); showHome(); return; }
        super.onBackPressed();
    }

    private void showHome() {
        clearEditorState();
        currentScreen = "home";
        LinearLayout page = basePage("Controle de Motos", "Piu • oficina rápida, local e offline", false);
        LinearLayout body = bodyOf(page);

        Button newService = button("+ Novo atendimento", C.blue);
        newService.setTextSize(17);
        newService.setMinHeight(dp(60));
        newService.setOnClickListener(v -> showServiceEditor(null));
        body.addView(newService, full(0, 8));

        LinearLayout searchRow = horizontal();
        EditText search = input("Buscar placa, serviço, peça ou observação");
        search.setText(searchText);
        Button searchBtn = button("Buscar", C.dark);
        searchBtn.setOnClickListener(v -> { searchText = search.getText().toString().trim(); showHome(); });
        search.setOnEditorActionListener((v, actionId, event) -> { searchText = search.getText().toString().trim(); showHome(); return true; });
        searchRow.addView(search, weight(1));
        searchRow.addView(searchBtn, fixed(94));
        body.addView(searchRow, full(2, 7));

        LinearLayout row1 = horizontal();
        Button ai = quickButton("IA local", "Consulte os dados");
        Button reports = quickButton("Relatórios", "Resumo e PDF");
        ai.setOnClickListener(v -> showAssistantPage());
        reports.setOnClickListener(v -> showReportsPage());
        row1.addView(ai, weight(1)); row1.addView(reports, weight(1));
        body.addView(row1, full(0, 2));

        LinearLayout row2 = horizontal();
        Button backup = quickButton("Backup", "Salvar e restaurar");
        Button select = quickButton(selectionMode ? "Cancelar seleção" : "Selecionar", selectionMode ? "Voltar à lista" : "Vários atendimentos");
        backup.setOnClickListener(v -> showBackupPage());
        select.setOnClickListener(v -> { selectionMode = !selectionMode; selectedIds.clear(); showHome(); });
        row2.addView(backup, weight(1)); row2.addView(select, weight(1));
        body.addView(row2, full(0, 7));

        Calendar now = Calendar.getInstance();
        Calendar first = (Calendar) now.clone(); first.set(Calendar.DAY_OF_MONTH, 1);
        DatabaseHelper.Summary month = db.summary(isoDay.format(first.getTime()), isoDay.format(now.getTime()));
        LinearLayout monthCard = card(0xFFF8FAFC, 0xFFDDE5EF, 16);
        LinearLayout monthLine = horizontal();
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        left.addView(tv("ESTE MÊS", 9, true, C.muted));
        left.addView(tv(month.count + " atendimento(s)", 14, true, C.text));
        TextView monthTotal = tv(money(month.total), 17, true, C.green); monthTotal.setGravity(Gravity.END);
        monthLine.addView(left, weight(1)); monthLine.addView(monthTotal, wrap());
        monthCard.addView(monthLine);
        body.addView(monthCard, full(0, 8));

        if (selectionMode) {
            LinearLayout selectedBar = card(0xFFEEF4FF, 0xFFBFDBFE, 16);
            selectionCount = tv("0 selecionados", 13, true, C.text);
            selectedBar.addView(selectionCount, full(0, 4));
            LinearLayout row = horizontal();
            selectedReportButton = button("PDF", C.blue);
            selectedDeleteButton = button("Excluir", C.red);
            Button cancel = softButton("Cancelar");
            selectedReportButton.setEnabled(false); selectedDeleteButton.setEnabled(false);
            selectedReportButton.setOnClickListener(v -> shareSelectedReport());
            selectedDeleteButton.setOnClickListener(v -> confirmDeleteSelected());
            cancel.setOnClickListener(v -> { selectionMode = false; selectedIds.clear(); showHome(); });
            row.addView(selectedReportButton, weight(1)); row.addView(selectedDeleteButton, weight(1)); row.addView(cancel, weight(1));
            selectedBar.addView(row);
            body.addView(selectedBar, full(0, 8));
        }

        Cursor c = db.services(searchText, null, null);
        int shown = 0;
        try {
            while (c.moveToNext()) {
                shown++;
                body.addView(serviceCard(c), full(0, 10));
            }
        } finally { c.close(); }

        if (shown == 0) {
            TextView empty = tv(searchText.isEmpty() ? "Nenhum atendimento ainda.\nToque em “Novo atendimento”." : "Nenhum atendimento encontrado.", 14, true, C.muted);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(30), dp(20), dp(30));
            body.addView(empty, full(5, 10));
        }

        footer(body);
        setContentView(page);
    }

    private View serviceCard(Cursor c) {
        long id = c.getLong(c.getColumnIndexOrThrow("id"));
        String plate = c.getString(c.getColumnIndexOrThrow("plate"));
        long km = c.getLong(c.getColumnIndexOrThrow("km"));
        String date = c.getString(c.getColumnIndexOrThrow("service_date"));
        String service = c.getString(c.getColumnIndexOrThrow("service_text"));
        int servicesCount = c.getInt(c.getColumnIndexOrThrow("service_items_count"));
        int partsCount = c.getInt(c.getColumnIndexOrThrow("parts_count"));
        double total = c.getDouble(c.getColumnIndexOrThrow("labor_value")) + c.getDouble(c.getColumnIndexOrThrow("parts_total"));

        LinearLayout card = card(Color.WHITE, 0xFFDCE3EC, 20);
        card.setPadding(0, 0, 0, 0);
        card.setElevation(dp(2));

        LinearLayout head = horizontal();
        head.setPadding(dp(14), dp(14), dp(14), dp(13));
        head.setBackground(bg(C.navy, C.navy, 20));
        head.addView(mercosulPlate(plate), wrap());

        LinearLayout kmBox = new LinearLayout(this); kmBox.setOrientation(LinearLayout.VERTICAL); kmBox.setGravity(Gravity.END);
        TextView kmVal = tv(formatInt(km) + " KM", 16, true, C.sky); kmVal.setGravity(Gravity.END);
        TextView kmLabel = tv("ENTRADA", 9, true, 0xFF9AA8BC); kmLabel.setGravity(Gravity.END);
        kmBox.addView(kmVal); kmBox.addView(kmLabel);
        head.addView(kmBox, weight(1));

        if (selectionMode) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(selectedIds.contains(id));
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(C.sky));
            cb.setOnCheckedChangeListener((buttonView, checked) -> { if (checked) selectedIds.add(id); else selectedIds.remove(id); updateSelectionBar(); });
            head.addView(cb, fixed(48));
            card.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        } else {
            card.setOnClickListener(v -> showServiceEditor(id));
            card.setOnLongClickListener(v -> { selectionMode = true; selectedIds.clear(); selectedIds.add(id); showHome(); return true; });
        }
        card.addView(head, fullNoMargin());

        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(15), dp(12), dp(15), dp(12));
        LinearLayout top = horizontal();
        TextView dateTv = tv(br(date), 12, true, C.muted);
        TextView totalTv = tv(money(total), 16, true, C.green); totalTv.setGravity(Gravity.END);
        top.addView(dateTv, weight(1)); top.addView(totalTv, wrap());
        info.addView(top);

        TextView svc = tv(service, 14, true, C.text); svc.setMaxLines(2); svc.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(svc, full(1, 2));
        TextView meta = tv(servicesCount + " serviço(s) • " + partsCount + " peça(s)", 10, false, C.muted);
        info.addView(meta, full(0, 4));

        if (!selectionMode) {
            LinearLayout actions = horizontal();
            Button edit = miniButton("Editar");
            Button pdf = miniButton("PDF");
            Button del = miniButton("Excluir"); del.setTextColor(C.red);
            edit.setOnClickListener(v -> showServiceEditor(id));
            pdf.setOnClickListener(v -> shareSingleReport(id));
            del.setOnClickListener(v -> confirmDeleteOne(id));
            actions.addView(edit, weight(1)); actions.addView(pdf, weight(1)); actions.addView(del, weight(1));
            info.addView(actions, full(0, 0));
        }
        card.addView(info, fullNoMargin());
        return card;
    }

    private View mercosulPlate(String plate) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setGravity(Gravity.CENTER);
        p.setBackground(bg(Color.WHITE, 0xFFCBD5E1, 6)); p.setPadding(dp(1), dp(1), dp(1), dp(1));
        TextView top = tv("BRASIL", 8, true, Color.WHITE); top.setGravity(Gravity.CENTER); top.setLetterSpacing(0.14f); top.setBackgroundColor(0xFF003399); top.setPadding(dp(12), dp(2), dp(12), dp(2));
        TextView txt = tv(DatabaseHelper.displayPlate(plate), 18, true, Color.BLACK); txt.setGravity(Gravity.CENTER); txt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); txt.setLetterSpacing(0.10f); txt.setPadding(dp(11), dp(4), dp(11), dp(5));
        p.addView(top, new LinearLayout.LayoutParams(-1, dp(18))); p.addView(txt);
        return p;
    }

    private void updateSelectionBar() {
        if (selectionCount != null) selectionCount.setText(selectedIds.size() + " selecionado(s)");
        boolean enabled = !selectedIds.isEmpty();
        if (selectedReportButton != null) selectedReportButton.setEnabled(enabled);
        if (selectedDeleteButton != null) selectedDeleteButton.setEnabled(enabled);
    }

    private void showServiceEditor(Long id) {
        clearEditorState();
        currentScreen = "editor";
        editingId = id;
        LinearLayout page = basePage(id == null ? "Novo atendimento" : "Editar atendimento", "Moto, serviços, peças e valores", true);
        LinearLayout body = bodyOf(page);

        sectionLabel(body, "DADOS DA MOTO", "Informações essenciais do atendimento");
        plateInput = input("ABC1D23");
        plateInput.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(8)});
        plateInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        kmInput = numberInput("KM");
        dateInput = input("dd/mm/aaaa"); dateInput.setFocusable(false); dateInput.setOnClickListener(v -> pickDate(dateInput));
        LinearLayout dataRow = horizontal(); dataRow.addView(field("Placa", plateInput), weight(1)); dataRow.addView(field("KM", kmInput), weight(1));
        body.addView(dataRow, full(0, 4));
        body.addView(field("Data", dateInput), full(0, 9));

        LinearLayout servicesTitle = horizontal();
        LinearLayout servicesNames = new LinearLayout(this); servicesNames.setOrientation(LinearLayout.VERTICAL);
        servicesNames.addView(tv("SERVIÇOS", 13, true, C.text));
        servicesNames.addView(tv("Adicione cada serviço em uma linha", 10, false, C.muted));
        servicesTitle.addView(servicesNames, weight(1));
        Button addService = softButton("+ Serviço"); addService.setOnClickListener(v -> addServiceRow(null, 0));
        servicesTitle.addView(addService, wrap());
        body.addView(servicesTitle, full(0, 4));
        servicesContainer = new LinearLayout(this); servicesContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(servicesContainer, full(0, 8));

        LinearLayout partsTitle = horizontal();
        LinearLayout partsNames = new LinearLayout(this); partsNames.setOrientation(LinearLayout.VERTICAL);
        partsNames.addView(tv("PEÇAS", 13, true, C.text));
        partsNames.addView(tv("Opcional • quantidade e valor unitário", 10, false, C.muted));
        partsTitle.addView(partsNames, weight(1));
        Button addPart = softButton("+ Peça"); addPart.setOnClickListener(v -> addPartRow(null, 1, 0));
        partsTitle.addView(addPart, wrap());
        body.addView(partsTitle, full(0, 4));
        partsContainer = new LinearLayout(this); partsContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(partsContainer, full(0, 8));

        LinearLayout totals = card(0xFFF8FAFC, 0xFFD8E1EC, 18);
        servicesTotalText = tv("Serviços: R$ 0,00", 12, true, C.muted);
        partsTotalText = tv("Peças: R$ 0,00", 12, true, C.muted);
        grandTotalText = tv("TOTAL: R$ 0,00", 20, true, C.text);
        totals.addView(servicesTotalText); totals.addView(partsTotalText); totals.addView(grandTotalText);
        body.addView(totals, full(0, 9));

        notesInput = multiline("Observação, recomendação, retorno...");
        body.addView(field("Observação", notesInput), full(0, 8));

        Button save = button("Salvar atendimento", C.blue); save.setMinHeight(dp(58)); save.setTextSize(15); save.setOnClickListener(v -> saveEditor());
        body.addView(save, full(0, 6));

        if (id != null) {
            LinearLayout existingActions = horizontal();
            Button share = button("Enviar PDF", C.green); share.setOnClickListener(v -> shareSingleReport(id));
            Button del = button("Excluir", C.red); del.setOnClickListener(v -> confirmDeleteOne(id));
            existingActions.addView(share, weight(1)); existingActions.addView(del, weight(1));
            body.addView(existingActions, full(0, 6));
            loadServiceIntoEditor(id);
        } else {
            dateInput.setText(br(isoDay.format(new Date())));
            addServiceRow(null, 0);
        }

        footer(body);
        setContentView(page);
    }

    private void loadServiceIntoEditor(long id) {
        Cursor c = db.service(id);
        try {
            if (!c.moveToFirst()) { toast("Atendimento não encontrado."); showHome(); return; }
            plateInput.setText(DatabaseHelper.displayPlate(c.getString(c.getColumnIndexOrThrow("plate"))));
            kmInput.setText(String.valueOf(c.getLong(c.getColumnIndexOrThrow("km"))));
            dateInput.setText(br(c.getString(c.getColumnIndexOrThrow("service_date"))));
            notesInput.setText(c.getString(c.getColumnIndexOrThrow("notes")));
        } finally { c.close(); }

        Cursor items = db.serviceItems(id);
        try {
            while (items.moveToNext()) addServiceRow(
                    items.getString(items.getColumnIndexOrThrow("description")),
                    items.getDouble(items.getColumnIndexOrThrow("value")));
        } finally { items.close(); }
        if (serviceRows.isEmpty()) addServiceRow("Serviço realizado", 0);

        Cursor p = db.parts(id);
        try {
            while (p.moveToNext()) addPartRow(
                    p.getString(p.getColumnIndexOrThrow("description")),
                    p.getDouble(p.getColumnIndexOrThrow("quantity")),
                    p.getDouble(p.getColumnIndexOrThrow("unit_value")));
        } finally { p.close(); }
        refreshTotals();
    }

    private void addServiceRow(String description, double serviceValue) {
        LinearLayout box = card(Color.WHITE, 0xFFDCE3EC, 16);
        TextView index = tv("Serviço " + (serviceRows.size() + 1), 10, true, C.muted);
        box.addView(index, full(0, 2));
        EditText desc = input("Ex.: revisão freio dianteiro"); if (description != null) desc.setText(description);
        box.addView(field("Descrição", desc), full(0, 4));
        LinearLayout row = horizontal();
        EditText val = moneyInput("0,00"); if (serviceValue > 0) val.setText(decimal(serviceValue));
        Button remove = button("×", C.gray); remove.setTextSize(19);
        row.addView(field("Valor do serviço (R$)", val), weight(1)); row.addView(remove, fixed(54));
        box.addView(row);
        ServiceRow sr = new ServiceRow(box, desc, val, index); serviceRows.add(sr); servicesContainer.addView(box, full(0, 6));
        val.addTextChangedListener(totalWatcher());
        remove.setOnClickListener(v -> {
            if (serviceRows.size() <= 1) { toast("Mantenha pelo menos um serviço."); return; }
            servicesContainer.removeView(box); serviceRows.remove(sr); renumberServices(); refreshTotals();
        });
        refreshTotals();
    }

    private void renumberServices() {
        for (int i = 0; i < serviceRows.size(); i++) serviceRows.get(i).index.setText("Serviço " + (i + 1));
    }

    private void addPartRow(String description, double quantity, double unitValue) {
        LinearLayout box = card(Color.WHITE, 0xFFDCE3EC, 16);
        EditText desc = input("Descrição da peça"); if (description != null) desc.setText(description);
        box.addView(field("Peça", desc), full(0, 4));
        LinearLayout row = horizontal();
        EditText qty = numberInput("Qtd."); qty.setText(decimalQty(quantity));
        EditText unit = moneyInput("0,00"); if (unitValue > 0) unit.setText(decimal(unitValue));
        Button remove = button("×", C.gray); remove.setTextSize(19);
        row.addView(field("Qtd.", qty), weight(1)); row.addView(field("Valor unit. (R$)", unit), weight(1)); row.addView(remove, fixed(54));
        box.addView(row);
        TextView subtotal = tv("Subtotal: R$ 0,00", 11, true, C.muted); subtotal.setGravity(Gravity.END); box.addView(subtotal, full(1, 0));
        PartRow pr = new PartRow(box, desc, qty, unit, subtotal); partRows.add(pr); partsContainer.addView(box, full(0, 6));
        TextWatcher watcher = totalWatcher(); qty.addTextChangedListener(watcher); unit.addTextChangedListener(watcher);
        remove.setOnClickListener(v -> { partsContainer.removeView(box); partRows.remove(pr); refreshTotals(); });
        refreshTotals();
    }

    private TextWatcher totalWatcher() {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { refreshTotals(); }
            public void afterTextChanged(Editable e) { }
        };
    }

    private void refreshTotals() {
        double services = 0;
        for (ServiceRow s : serviceRows) services += value(s.value);
        double parts = 0;
        for (PartRow p : partRows) {
            double subtotal = value(p.qty) * value(p.unit);
            parts += subtotal;
            p.subtotal.setText("Subtotal: " + money(subtotal));
        }
        if (servicesTotalText != null) servicesTotalText.setText("Serviços: " + money(services));
        if (partsTotalText != null) partsTotalText.setText("Peças: " + money(parts));
        if (grandTotalText != null) grandTotalText.setText("TOTAL: " + money(services + parts));
    }

    private void saveEditor() {
        try {
            String dateIso = toIso(dateInput.getText().toString());
            JSONArray services = new JSONArray();
            for (ServiceRow s : serviceRows) {
                String desc = s.desc.getText().toString().trim();
                if (desc.isEmpty()) continue;
                JSONObject j = new JSONObject(); j.put("description", desc); j.put("value", value(s.value)); services.put(j);
            }
            JSONArray parts = new JSONArray();
            for (PartRow p : partRows) {
                String d = p.desc.getText().toString().trim();
                if (d.isEmpty()) continue;
                JSONObject j = new JSONObject(); j.put("description", d); j.put("quantity", value(p.qty)); j.put("unitValue", value(p.unit)); parts.put(j);
            }
            boolean wasNew = editingId == null;
            db.saveService(editingId, plateInput.getText().toString(), longValue(kmInput), dateIso,
                    services, notesInput.getText().toString(), parts, nowFmt.format(new Date()));
            toast(wasNew ? "Atendimento salvo." : "Atendimento atualizado.");
            showHome();
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void showAssistantPage() {
        currentScreen = "assistant";
        LinearLayout page = basePage("IA local", "Consulta tudo que foi lançado no celular", true);
        LinearLayout body = bodyOf(page);
        LinearLayout info = card(0xFFEEF4FF, 0xFFBFDBFE, 16);
        info.addView(tv("Sem internet e sem Firebase", 12, true, C.blue));
        info.addView(tv("A consulta usa placas, KM, datas, serviços, peças, valores e observações salvas no aparelho.", 11, false, C.muted));
        body.addView(info, full(0, 8));

        EditText q = multiline("Ex.: o que foi feito na ABC1D23?\nQuanto deu este mês?\nQuais peças mais usei?");
        q.setMinHeight(dp(110)); body.addView(field("Pergunte", q), full(0, 7));
        Button ask = button("Consultar", C.blue); ask.setMinHeight(dp(54)); body.addView(ask, full(0, 8));
        TextView answer = tv("A resposta aparecerá aqui.", 13, false, C.muted);
        answer.setPadding(dp(14), dp(14), dp(14), dp(14)); answer.setBackground(bg(Color.WHITE, 0xFFDCE3EC, 16));
        body.addView(answer, full(0, 8));
        ask.setOnClickListener(v -> { answer.setText(db.answerLocalAssistant(q.getText().toString())); answer.setTextColor(C.text); });
        footer(body);
        setContentView(page);
    }

    private void showReportsPage() {
        currentScreen = "reports";
        LinearLayout page = basePage("Relatórios", "Período, resumo e compartilhamento", true);
        LinearLayout body = bodyOf(page);
        Calendar now = Calendar.getInstance(); Calendar first = (Calendar) now.clone(); first.set(Calendar.DAY_OF_MONTH, 1);
        EditText start = input("Início"); start.setFocusable(false); start.setText(br(isoDay.format(first.getTime()))); start.setOnClickListener(v -> pickDate(start));
        EditText end = input("Fim"); end.setFocusable(false); end.setText(br(isoDay.format(now.getTime()))); end.setOnClickListener(v -> pickDate(end));
        LinearLayout dates = horizontal(); dates.addView(field("De", start), weight(1)); dates.addView(field("Até", end), weight(1));
        body.addView(dates, full(0, 8));

        TextView summary = tv("", 14, true, C.text); summary.setPadding(dp(14), dp(14), dp(14), dp(14)); summary.setBackground(bg(Color.WHITE, 0xFFDCE3EC, 16));
        body.addView(summary, full(0, 7));
        Button update = softButton("Atualizar resumo");
        Button detailed = button("Compartilhar PDF detalhado", C.blue);
        Button simple = button("Compartilhar PDF resumido", C.navy);
        body.addView(update, full(0, 5)); body.addView(detailed, full(0, 5)); body.addView(simple, full(0, 7));

        Runnable refresh = () -> {
            try {
                DatabaseHelper.Summary s = db.summary(toIso(start.getText().toString()), toIso(end.getText().toString()));
                summary.setText(s.count + " atendimento(s)\nServiços: " + money(s.labor) + "\nPeças: " + money(s.parts) + "\nTOTAL: " + money(s.total));
            } catch (Exception e) { summary.setText("Confira as datas informadas."); }
        };
        update.setOnClickListener(v -> refresh.run());
        detailed.setOnClickListener(v -> { try { shareRangeReport(toIso(start.getText().toString()), toIso(end.getText().toString()), true); } catch (Exception e) { error(e.getMessage()); } });
        simple.setOnClickListener(v -> { try { shareRangeReport(toIso(start.getText().toString()), toIso(end.getText().toString()), false); } catch (Exception e) { error(e.getMessage()); } });
        refresh.run();
        footer(body);
        setContentView(page);
    }

    private void showBackupPage() {
        currentScreen = "backup";
        LinearLayout page = basePage("Backup e exportação", "Dados locais do aparelho", true);
        LinearLayout body = bodyOf(page);
        LinearLayout info = card(0xFFF8FAFC, 0xFFDCE3EC, 16);
        info.addView(tv("Os atendimentos ficam neste celular.", 12, true, C.text));
        info.addView(tv("Exporte um backup para restaurar tudo em outro aparelho quando precisar.", 11, false, C.muted));
        body.addView(info, full(0, 9));

        Button exp = button("Exportar backup completo", C.blue);
        Button imp = button("Importar / restaurar backup", C.green);
        Button csv = softButton("Exportar planilha CSV");
        exp.setOnClickListener(v -> createDocument("application/json", "backup-controle-motos-piu-" + isoDay.format(new Date()) + ".json", REQ_EXPORT_BACKUP));
        imp.setOnClickListener(v -> openDocument());
        csv.setOnClickListener(v -> createDocument("text/csv", "atendimentos-piu-" + isoDay.format(new Date()) + ".csv", REQ_EXPORT_CSV));
        body.addView(exp, full(0, 6)); body.addView(imp, full(0, 6)); body.addView(csv, full(0, 8));
        footer(body);
        setContentView(page);
    }

    private void createDocument(String type, String name, int requestCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(type); i.putExtra(Intent.EXTRA_TITLE, name); startActivityForResult(i, requestCode);
    }

    private void openDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json"); startActivityForResult(i, REQ_IMPORT_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) exportBackup(uri);
        else if (requestCode == REQ_EXPORT_CSV) exportCsv(uri);
        else if (requestCode == REQ_IMPORT_BACKUP) confirmImport(uri);
    }

    private void exportBackup(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("Não foi possível criar o arquivo.");
            JSONObject root = db.exportAll(); root.put("exportedAt", nowFmt.format(new Date()));
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8)); toast("Backup exportado.");
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void exportCsv(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("Não foi possível criar o arquivo.");
            String csv = "\uFEFF" + db.exportCsv(null, null); out.write(csv.getBytes(StandardCharsets.UTF_8)); toast("Planilha CSV exportada.");
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void confirmImport(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Restaurar backup?")
                .setMessage("O backup substituirá os dados atuais do aplicativo. Faça uma exportação antes se precisar preservar o que já está salvo.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Restaurar", (d, w) -> importBackup(uri)).show();
    }

    private void importBackup(Uri uri) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line; while ((line = r.readLine()) != null) sb.append(line);
            db.importAll(new JSONObject(sb.toString())); toast("Backup restaurado com sucesso."); searchText = ""; showHome();
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void confirmDeleteOne(long id) {
        new AlertDialog.Builder(this).setTitle("Excluir atendimento?").setMessage("Esta ação não pode ser desfeita.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { db.deleteService(id); toast("Atendimento excluído."); showHome(); }).show();
    }

    private void confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("Excluir selecionados?").setMessage("Excluir definitivamente " + selectedIds.size() + " atendimento(s)?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { int n = db.deleteServices(new HashSet<>(selectedIds)); toast(n + " atendimento(s) excluído(s)."); selectedIds.clear(); selectionMode = false; showHome(); }).show();
    }

    private void shareSingleReport(long id) {
        try { List<Long> one = new ArrayList<>(); one.add(id); sharePdf(buildReportPdf("Relatório de Atendimento", null, null, one, true), "relatorio-atendimento-piu.pdf"); }
        catch (Exception e) { error(e.getMessage()); }
    }

    private void shareSelectedReport() {
        try { sharePdf(buildReportPdf("Atendimentos Selecionados", null, null, new ArrayList<>(selectedIds), true), "relatorio-selecionados-piu.pdf"); }
        catch (Exception e) { error(e.getMessage()); }
    }

    private void shareRangeReport(String start, String end, boolean detailed) {
        try { sharePdf(buildReportPdf(detailed ? "Relatório Detalhado" : "Relatório Resumido", start, end, null, detailed), "relatorio-piu-" + start + "-a-" + end + ".pdf"); }
        catch (Exception e) { error(e.getMessage()); }
    }

    private File buildReportPdf(String title, String start, String end, List<Long> ids, boolean detailed) throws Exception {
        File dir = new File(getCacheDir(), "reports"); if (!dir.exists() && !dir.mkdirs()) throw new Exception("Não foi possível preparar o relatório.");
        File file = new File(dir, "piu-report-" + System.currentTimeMillis() + ".pdf");
        PdfDocument doc = new PdfDocument(); PdfWriter writer = new PdfWriter(doc);
        writer.header(title, start != null || end != null ? "Período: " + (start == null ? "início" : br(start)) + " a " + (end == null ? "hoje" : br(end)) : "Controle de Motos • Piu");

        double servicesSum = 0, partsSum = 0; int count = 0;
        Cursor c = ids == null ? db.services("", start, end) : null;
        try {
            if (ids == null) {
                while (c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndexOrThrow("id"));
                    servicesSum += c.getDouble(c.getColumnIndexOrThrow("labor_value"));
                    partsSum += c.getDouble(c.getColumnIndexOrThrow("parts_total"));
                    count++; writeService(writer, c, id, detailed);
                }
            } else {
                for (Long id : ids) {
                    Cursor one = db.service(id);
                    try {
                        if (one.moveToFirst()) {
                            servicesSum += one.getDouble(one.getColumnIndexOrThrow("labor_value"));
                            partsSum += one.getDouble(one.getColumnIndexOrThrow("parts_total"));
                            count++; writeService(writer, one, id, detailed);
                        }
                    } finally { one.close(); }
                }
            }
        } finally { if (c != null) c.close(); }
        writer.summary(count, servicesSum, partsSum);
        writer.finish();
        try (FileOutputStream out = new FileOutputStream(file)) { doc.writeTo(out); } finally { doc.close(); }
        return file;
    }

    private void writeService(PdfWriter w, Cursor c, long id, boolean detailed) {
        double services = c.getDouble(c.getColumnIndexOrThrow("labor_value"));
        double parts = c.getDouble(c.getColumnIndexOrThrow("parts_total"));
        String plate = DatabaseHelper.displayPlate(c.getString(c.getColumnIndexOrThrow("plate")));
        String date = br(c.getString(c.getColumnIndexOrThrow("service_date")));
        long km = c.getLong(c.getColumnIndexOrThrow("km"));
        w.serviceHeader(plate, date, formatInt(km) + " km", money(services + parts));

        if (!detailed) {
            w.line(c.getString(c.getColumnIndexOrThrow("service_text")), 10, true, C.text, 0);
            w.spacer(7); return;
        }

        w.section("SERVIÇOS");
        Cursor items = db.serviceItems(id);
        try {
            while (items.moveToNext()) w.item(items.getString(items.getColumnIndexOrThrow("description")), money(items.getDouble(items.getColumnIndexOrThrow("value"))), C.blue);
        } finally { items.close(); }

        Cursor p = db.parts(id);
        boolean hasParts = false;
        try {
            while (p.moveToNext()) {
                if (!hasParts) { w.section("PEÇAS"); hasParts = true; }
                double qty = p.getDouble(p.getColumnIndexOrThrow("quantity"));
                double unit = p.getDouble(p.getColumnIndexOrThrow("unit_value"));
                String left = p.getString(p.getColumnIndexOrThrow("description")) + "  •  " + decimalQty(qty) + " × " + money(unit);
                w.item(left, money(qty * unit), C.muted);
            }
        } finally { p.close(); }

        String notes = c.getString(c.getColumnIndexOrThrow("notes"));
        if (notes != null && !notes.trim().isEmpty()) {
            w.section("OBSERVAÇÃO");
            w.line(notes.trim(), 9.5f, false, C.muted, 0);
        }
        w.totals(services, parts);
        w.spacer(9);
    }

    private void sharePdf(File file, String subject) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("application/pdf");
        share.putExtra(Intent.EXTRA_STREAM, uri); share.putExtra(Intent.EXTRA_SUBJECT, subject);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share, "Enviar relatório"));
    }

    private class PdfWriter {
        final PdfDocument doc;
        PdfDocument.Page page;
        Canvas canvas;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int width = 595, height = 842, margin = 36;
        int pageNo = 0;
        float y = 0;

        PdfWriter(PdfDocument d) { doc = d; newPage(); }

        void newPage() {
            closePage();
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(width, height, ++pageNo).create();
            page = doc.startPage(info); canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
            if (pageNo > 1) {
                paint.setColor(C.navy); canvas.drawRect(0, 0, width, 34, paint);
                paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(9); paint.setColor(Color.WHITE);
                canvas.drawText("Piu • Controle de Motos", margin, 22, paint);
                y = 52;
            } else {
                y = margin;
            }
        }

        void closePage() {
            if (page == null) return;
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(8); paint.setColor(0xFF94A3B8);
            canvas.drawText("Powered by thIAguinho Soluções Digitais", margin, height - 22, paint);
            String pg = "Página " + pageNo; canvas.drawText(pg, width - margin - paint.measureText(pg), height - 22, paint);
            doc.finishPage(page); page = null;
        }

        void header(String title, String subtitle) {
            paint.setColor(C.navy); canvas.drawRoundRect(new RectF(0, 0, width, 105), 0, 0, paint);
            paint.setColor(C.blue); canvas.drawRect(0, 0, 8, 105, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(21); paint.setColor(Color.WHITE);
            canvas.drawText(title, margin, 48, paint);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(10); paint.setColor(0xFFD8E3F0);
            canvas.drawText(subtitle, margin, 70, paint);
            y = 126;
        }

        void serviceHeader(String plate, String date, String km, String total) {
            ensure(78);
            float top = y;
            paint.setColor(0xFFF8FAFC); canvas.drawRoundRect(new RectF(margin, top, width - margin, top + 64), 10, 10, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(0xFFDCE3EC); canvas.drawRoundRect(new RectF(margin, top, width - margin, top + 64), 10, 10, paint); paint.setStyle(Paint.Style.FILL);

            float px = margin + 10, py = top + 10, pw = 118, ph = 44;
            paint.setColor(Color.WHITE); canvas.drawRoundRect(new RectF(px, py, px + pw, py + ph), 4, 4, paint);
            paint.setColor(0xFF003399); canvas.drawRect(px, py, px + pw, py + 12, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(6.5f); paint.setColor(Color.WHITE);
            centered("BRASIL", px + pw / 2, py + 8.5f);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); paint.setTextSize(15); paint.setColor(Color.BLACK);
            centered(plate, px + pw / 2, py + 34);

            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(9); paint.setColor(C.muted);
            canvas.drawText(date + "  •  " + km, px + pw + 16, top + 25, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(14); paint.setColor(C.green);
            canvas.drawText(total, px + pw + 16, top + 47, paint);
            y += 74;
        }

        void section(String title) {
            ensure(25); spacer(2);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(8); paint.setColor(C.blue);
            canvas.drawText(title, margin + 4, y, paint); y += 14;
        }

        void item(String left, String right, int accent) {
            ensure(30);
            paint.setColor(accent); canvas.drawRoundRect(new RectF(margin + 3, y - 8, margin + 6, y + 11), 2, 2, paint);
            float rightWidth;
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(9.5f); paint.setColor(C.text);
            rightWidth = paint.measureText(right);
            float maxLeft = width - margin * 2 - rightWidth - 32;
            List<String> lines = wrapText(left, paint, maxLeft);
            if (lines.isEmpty()) lines.add("");
            for (int i = 0; i < lines.size(); i++) {
                ensure(15);
                canvas.drawText(lines.get(i), margin + 12, y, paint);
                if (i == 0) {
                    paint.setColor(C.text); canvas.drawText(right, width - margin - rightWidth, y, paint);
                    paint.setColor(C.text);
                }
                y += 14;
            }
            y += 2;
        }

        void totals(double services, double parts) {
            ensure(56); spacer(4);
            paint.setColor(0xFFEEF4FF); canvas.drawRoundRect(new RectF(margin, y, width - margin, y + 47), 8, 8, paint);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(9); paint.setColor(C.muted);
            canvas.drawText("Serviços: " + money(services) + "    Peças: " + money(parts), margin + 12, y + 17, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(13); paint.setColor(C.text);
            canvas.drawText("TOTAL: " + money(services + parts), margin + 12, y + 36, paint);
            y += 55;
        }

        void summary(int count, double services, double parts) {
            ensure(92); spacer(6);
            paint.setColor(C.navy); canvas.drawRoundRect(new RectF(margin, y, width - margin, y + 78), 12, 12, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(10); paint.setColor(C.sky);
            canvas.drawText("RESUMO DO RELATÓRIO", margin + 14, y + 20, paint);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(9); paint.setColor(0xFFD7E0ED);
            canvas.drawText(count + " atendimento(s)  •  Serviços " + money(services) + "  •  Peças " + money(parts), margin + 14, y + 41, paint);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(16); paint.setColor(Color.WHITE);
            canvas.drawText("TOTAL  " + money(services + parts), margin + 14, y + 65, paint);
            y += 88;
        }

        void line(String text, float size, boolean bold, int color, float indent) {
            if (text == null) text = "";
            paint.setTextSize(size); paint.setTypeface(bold ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD) : Typeface.DEFAULT); paint.setColor(color);
            float max = width - margin * 2 - indent;
            for (String paragraph : text.split("\\n", -1)) {
                List<String> lines = wrapText(paragraph, paint, max);
                if (lines.isEmpty()) lines.add("");
                for (String l : lines) { ensure(size + 8); canvas.drawText(l, margin + indent, y, paint); y += size + 5; }
            }
        }

        void spacer(float h) { ensure(h); y += h; }
        void ensure(float need) { if (y + need > height - 46) newPage(); }
        void centered(String text, float centerX, float baseline) { canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint); }

        List<String> wrapText(String text, Paint p, float max) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isEmpty()) { out.add(""); return out; }
            String[] words = text.split("\\s+"); StringBuilder line = new StringBuilder();
            for (String word : words) {
                String test = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(test) > max && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
                else { if (line.length() > 0) line.append(' '); line.append(word); }
            }
            if (line.length() > 0) out.add(line.toString());
            return out;
        }

        void finish() { closePage(); }
    }

    private LinearLayout basePage(String title, String subtitle, boolean back) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(C.bg);
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(12)); header.setBackgroundColor(C.dark);
        if (back) {
            Button b = backButton(); b.setOnClickListener(v -> showHome()); header.addView(b, fixed(50));
        } else {
            ImageView logo = new ImageView(this); logo.setImageResource(com.thiaguinho.controlemotospiu.R.drawable.brand_mark); logo.setPadding(dp(2), dp(2), dp(2), dp(2));
            header.addView(logo, fixed(48));
        }
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(title, 19, true, Color.WHITE); TextView s = tv(subtitle, 10, false, 0xFFCBD5E1);
        names.addView(t); names.addView(s); header.addView(names, weight(1));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setTag("BODY"); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(dp(14), dp(12) + bars.top, dp(14), dp(12));
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private Button backButton() {
        Button b = button("←", 0xFF263247); b.setTextSize(20); b.setContentDescription("Voltar"); b.setMinWidth(dp(46)); return b;
    }

    private LinearLayout bodyOf(LinearLayout root) { ScrollView s = (ScrollView) root.getChildAt(1); return (LinearLayout) s.getChildAt(0); }

    private void sectionLabel(LinearLayout body, String title, String subtitle) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tv(title, 12, true, C.text)); box.addView(tv(subtitle, 10, false, C.muted));
        body.addView(box, full(0, 7));
    }

    private void footer(LinearLayout body) {
        TextView f = tv("Powered by thIAguinho Soluções Digitais", 9, false, 0xFF94A3B8);
        f.setGravity(Gravity.CENTER); f.setPadding(dp(8), dp(20), dp(8), dp(14)); body.addView(f, full(0, 0));
    }

    private LinearLayout card(int fill, int stroke, int radius) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(13), dp(13), dp(13), dp(13));
        v.setBackground(bg(fill, stroke, radius)); v.setElevation(dp(1)); return v;
    }

    private LinearLayout field(String label, View input) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        TextView l = tv(label, 10, true, C.muted); l.setPadding(dp(2), 0, 0, dp(4)); b.addView(l); b.addView(input, new LinearLayout.LayoutParams(-1, -2)); return b;
    }

    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    private EditText input(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextSize(15); e.setSingleLine(true); e.setTextColor(C.text); e.setHintTextColor(0xFF9AA8BC);
        e.setBackground(bg(Color.WHITE, 0xFFCCD6E2, 13)); e.setPadding(dp(12), 0, dp(12), 0); e.setMinHeight(dp(50)); return e;
    }

    private EditText multiline(String hint) {
        EditText e = input(hint); e.setSingleLine(false); e.setGravity(Gravity.TOP | Gravity.START); e.setPadding(dp(12), dp(12), dp(12), dp(12)); e.setMinHeight(dp(84));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); return e;
    }

    private EditText numberInput(String hint) { EditText e = input(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); return e; }
    private EditText moneyInput(String hint) { return numberInput(hint); }

    private Button button(String text, int color) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(12); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(8), 0, dp(8), 0); b.setMinHeight(dp(48)); b.setBackground(bg(color, color, 13)); return b;
    }

    private Button softButton(String text) {
        Button b = button(text, 0xFFF8FAFC); b.setTextColor(C.text); b.setBackground(bg(0xFFF8FAFC, 0xFFDCE3EC, 13)); return b;
    }

    private Button quickButton(String title, String subtitle) {
        Button b = softButton(title + "\n" + subtitle); b.setTextSize(11); b.setMinHeight(dp(60)); b.setGravity(Gravity.CENTER); return b;
    }

    private Button miniButton(String text) {
        Button b = softButton(text); b.setTextSize(10); b.setMinHeight(dp(38)); return b;
    }

    private TextView tv(String text, int size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setPadding(dp(3), dp(3), dp(3), dp(3)); return t;
    }

    private GradientDrawable bg(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(radius)); g.setStroke(dp(1), stroke); return g;
    }

    private LinearLayout.LayoutParams full(int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(top), 0, dp(bottom)); return p; }
    private LinearLayout.LayoutParams fullNoMargin() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weight(float w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, w); p.setMargins(dp(3), dp(2), dp(3), dp(2)); return p; }
    private LinearLayout.LayoutParams fixed(int w) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(w), -2); p.setMargins(dp(3), dp(2), dp(3), dp(2)); return p; }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void pickDate(EditText target) {
        Calendar cal = Calendar.getInstance();
        try { Date d = brDay.parse(target.getText().toString()); if (d != null) cal.setTime(d); } catch (Exception ignored) { }
        new DatePickerDialog(this, (view, y, m, d) -> target.setText(String.format(BR, "%02d/%02d/%04d", d, m + 1, y)), cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private double value(EditText e) {
        try {
            String s = e.getText().toString().trim().replace(" ", ""); if (s.isEmpty()) return 0;
            if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(',', '.'); else if (s.contains(",")) s = s.replace(',', '.');
            return Double.parseDouble(s);
        } catch (Exception x) { return 0; }
    }

    private long longValue(EditText e) { try { return Long.parseLong(e.getText().toString().replaceAll("[^0-9]", "")); } catch (Exception x) { return 0; } }
    private String toIso(String br) throws Exception { Date d = brDay.parse(br); if (d == null) throw new Exception("Data inválida."); return isoDay.format(d); }
    private String br(String iso) { try { Date d = isoDay.parse(iso); return d == null ? iso : brDay.format(d); } catch (Exception e) { return iso; } }
    private String money(double v) { return String.format(BR, "R$ %,.2f", v); }
    private String decimal(double v) { return String.format(BR, "%.2f", v); }
    private String decimalQty(double v) { return Math.abs(v - Math.rint(v)) < 0.000001 ? String.valueOf((long) Math.rint(v)) : String.format(BR, "%.2f", v); }
    private String formatInt(long v) { return String.format(BR, "%,d", v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void error(String s) { new AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(s == null ? "Erro inesperado." : s).setPositiveButton("OK", null).show(); }

    private void clearEditorState() {
        editingId = null; plateInput = null; kmInput = null; dateInput = null; notesInput = null;
        servicesContainer = null; partsContainer = null; servicesTotalText = null; partsTotalText = null; grandTotalText = null;
        serviceRows.clear(); partRows.clear();
    }

    private static class ServiceRow {
        final LinearLayout root; final EditText desc, value; final TextView index;
        ServiceRow(LinearLayout root, EditText desc, EditText value, TextView index) { this.root = root; this.desc = desc; this.value = value; this.index = index; }
    }

    private static class PartRow {
        final LinearLayout root; final EditText desc, qty, unit; final TextView subtotal;
        PartRow(LinearLayout root, EditText desc, EditText qty, EditText unit, TextView subtotal) { this.root = root; this.desc = desc; this.qty = qty; this.unit = unit; this.subtotal = subtotal; }
    }

    private static class C {
        static final int bg = 0xFFF2F5F9;
        static final int text = 0xFF101827;
        static final int muted = 0xFF64748B;
        static final int dark = 0xFF0B1220;
        static final int navy = 0xFF14213A;
        static final int blue = 0xFF2563EB;
        static final int sky = 0xFF60A5FA;
        static final int green = 0xFF16A34A;
        static final int red = 0xFFDC2626;
        static final int gray = 0xFF475569;
    }
}
