package com.thiaguinho.controlemotospiu;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

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

    private String searchText = "";
    private boolean selectionMode = false;
    private final Set<Long> selectedIds = new HashSet<>();
    private TextView selectionCount;
    private Button selectedReportButton, selectedDeleteButton;

    private Long editingId = null;
    private EditText plateInput, kmInput, dateInput, serviceInput, laborInput, notesInput;
    private LinearLayout partsContainer;
    private TextView partsTotalText, grandTotalText;
    private final List<PartRow> partRows = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DatabaseHelper(this);
        showHome();
    }

    @Override public void onBackPressed() {
        if (editingId != null || plateInput != null) { clearEditorState(); showHome(); return; }
        if (selectionMode) { selectionMode = false; selectedIds.clear(); showHome(); return; }
        super.onBackPressed();
    }

    private void showHome() {
        clearEditorState();
        LinearLayout page = basePage("Controle de Motos", "Piu • rápido, local e offline", false);
        LinearLayout body = bodyOf(page);

        Button newService = button("+ Novo serviço", C.blue);
        newService.setTextSize(16);
        newService.setMinHeight(dp(58));
        newService.setOnClickListener(v -> showServiceEditor(null));
        body.addView(newService, full(0, 7));

        LinearLayout searchRow = horizontal();
        EditText search = input("Buscar placa, serviço, peça ou observação");
        search.setText(searchText);
        Button searchBtn = button("Buscar", C.dark);
        searchBtn.setOnClickListener(v -> { searchText = search.getText().toString().trim(); showHome(); });
        search.setOnEditorActionListener((v, actionId, event) -> { searchText = search.getText().toString().trim(); showHome(); return true; });
        searchRow.addView(search, weight(1));
        searchRow.addView(searchBtn, fixed(92));
        body.addView(searchRow, full(4, 4));

        LinearLayout actions = horizontal();
        Button ai = softButton("IA local");
        Button reports = softButton("Relatórios");
        Button backup = softButton("Backup");
        Button select = softButton(selectionMode ? "Cancelar" : "Selecionar");
        ai.setOnClickListener(v -> showAssistantDialog());
        reports.setOnClickListener(v -> showReportsDialog());
        backup.setOnClickListener(v -> showBackupDialog());
        select.setOnClickListener(v -> { selectionMode = !selectionMode; selectedIds.clear(); showHome(); });
        actions.addView(ai, weight(1)); actions.addView(reports, weight(1)); actions.addView(backup, weight(1)); actions.addView(select, weight(1));
        body.addView(actions, full(2, 6));

        Calendar now = Calendar.getInstance();
        Calendar first = (Calendar) now.clone(); first.set(Calendar.DAY_OF_MONTH, 1);
        DatabaseHelper.Summary month = db.summary(isoDay.format(first.getTime()), isoDay.format(now.getTime()));
        TextView monthLine = tv("Este mês: " + month.count + " atendimento(s) • " + money(month.total), 12, true, C.muted);
        monthLine.setGravity(Gravity.CENTER);
        monthLine.setPadding(dp(10), dp(8), dp(10), dp(8));
        monthLine.setBackground(bg(0xFFF8FAFC, 0xFFE2E8F0, 12));
        body.addView(monthLine, full(3, 7));

        if (selectionMode) {
            LinearLayout selectedBar = card(0xFFEEF2FF, 0xFFC7D2FE, 14);
            selectionCount = tv("0 selecionados", 13, true, C.text);
            selectedBar.addView(selectionCount, full(0,3));
            LinearLayout row = horizontal();
            selectedReportButton = button("Relatório", C.blue);
            selectedDeleteButton = button("Excluir", C.red);
            Button cancel = button("Cancelar", C.gray);
            selectedReportButton.setEnabled(false); selectedDeleteButton.setEnabled(false);
            selectedReportButton.setOnClickListener(v -> shareSelectedReport());
            selectedDeleteButton.setOnClickListener(v -> confirmDeleteSelected());
            cancel.setOnClickListener(v -> { selectionMode=false; selectedIds.clear(); showHome(); });
            row.addView(selectedReportButton, weight(1)); row.addView(selectedDeleteButton, weight(1)); row.addView(cancel, weight(1));
            selectedBar.addView(row, full(0,0));
            body.addView(selectedBar, full(2,8));
        }

        Cursor c = db.services(searchText, null, null);
        int shown = 0;
        try {
            while (c.moveToNext()) {
                shown++;
                body.addView(serviceCard(c), full(0, 9));
            }
        } finally { c.close(); }

        if (shown == 0) {
            TextView empty = tv(searchText.isEmpty() ? "Nenhum atendimento ainda.\nToque em “Novo serviço”." : "Nenhum atendimento encontrado.", 14, true, C.muted);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(30), dp(20), dp(30));
            body.addView(empty, full(5,10));
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
        double total = c.getDouble(c.getColumnIndexOrThrow("labor_value")) + c.getDouble(c.getColumnIndexOrThrow("parts_total"));

        LinearLayout card = card(Color.WHITE, 0xFFE2E8F0, 18);
        card.setPadding(0,0,0,0);

        LinearLayout head = horizontal();
        head.setPadding(dp(14), dp(14), dp(14), dp(12));
        head.setBackground(bg(0xFF172033, 0xFF172033, 18));
        head.addView(mercosulPlate(plate), wrap());

        LinearLayout kmBox = new LinearLayout(this); kmBox.setOrientation(LinearLayout.VERTICAL); kmBox.setGravity(Gravity.END);
        TextView kmVal = tv(formatInt(km) + " KM", 15, true, 0xFF60A5FA); kmVal.setGravity(Gravity.END);
        TextView kmLabel = tv("ENTRADA", 9, true, 0xFF94A3B8); kmLabel.setGravity(Gravity.END);
        kmBox.addView(kmVal); kmBox.addView(kmLabel);
        head.addView(kmBox, weight(1));

        if (selectionMode) {
            CheckBox cb = new CheckBox(this); cb.setChecked(selectedIds.contains(id)); cb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF60A5FA));
            cb.setOnCheckedChangeListener((buttonView, checked) -> { if(checked) selectedIds.add(id); else selectedIds.remove(id); updateSelectionBar(); });
            head.addView(cb, fixed(48));
            card.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
        } else {
            card.setOnClickListener(v -> showServiceEditor(id));
            card.setOnLongClickListener(v -> { selectionMode=true; selectedIds.clear(); selectedIds.add(id); showHome(); return true; });
        }
        card.addView(head, fullNoMargin());

        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(14),dp(11),dp(14),dp(13));
        LinearLayout top = horizontal();
        TextView dateTv = tv(br(date), 12, true, C.muted);
        TextView totalTv = tv(money(total), 15, true, C.green); totalTv.setGravity(Gravity.END);
        top.addView(dateTv, weight(1)); top.addView(totalTv, wrap());
        info.addView(top);
        TextView svc = tv(service, 14, true, C.text); svc.setMaxLines(2); svc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(svc, full(0,0));
        card.addView(info, fullNoMargin());
        return card;
    }

    private View mercosulPlate(String plate) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setGravity(Gravity.CENTER);
        p.setBackground(bg(Color.WHITE, 0xFFCBD5E1, 5)); p.setPadding(dp(1),dp(1),dp(1),dp(1));
        TextView top = tv("BRASIL", 8, true, Color.WHITE); top.setGravity(Gravity.CENTER); top.setLetterSpacing(0.14f); top.setBackgroundColor(0xFF003399); top.setPadding(dp(12),dp(2),dp(12),dp(2));
        TextView txt = tv(DatabaseHelper.displayPlate(plate), 18, true, Color.BLACK); txt.setGravity(Gravity.CENTER); txt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); txt.setLetterSpacing(0.10f); txt.setPadding(dp(11),dp(4),dp(11),dp(5));
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
        editingId = id;
        partRows.clear();
        LinearLayout page = basePage(id == null ? "Novo serviço" : "Editar atendimento", "Dados essenciais da moto", true);
        LinearLayout body = bodyOf(page);

        plateInput = input("ABC1D23"); plateInput.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(8)}); plateInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        kmInput = numberInput("KM");
        dateInput = input("dd/mm/aaaa"); dateInput.setFocusable(false); dateInput.setOnClickListener(v -> pickDate(dateInput));
        LinearLayout row = horizontal(); row.addView(field("Placa", plateInput), weight(1)); row.addView(field("KM", kmInput), weight(1));
        body.addView(row, full(0,4)); body.addView(field("Data", dateInput), full(0,6));

        serviceInput = multiline("Ex.: troca de óleo, revisão, freio...");
        body.addView(field("Serviço realizado", serviceInput), full(0,6));

        LinearLayout partsTitle = horizontal();
        partsTitle.addView(tv("Peças", 15, true, C.text), weight(1));
        Button addPart = softButton("+ Adicionar peça"); addPart.setOnClickListener(v -> addPartRow(null, 1, 0));
        partsTitle.addView(addPart, wrap()); body.addView(partsTitle, full(0,3));
        partsContainer = new LinearLayout(this); partsContainer.setOrientation(LinearLayout.VERTICAL); body.addView(partsContainer, full(0,4));

        laborInput = moneyInput("0,00");
        body.addView(field("Mão de obra / serviço (R$)", laborInput), full(0,6));
        laborInput.addTextChangedListener(totalWatcher());

        LinearLayout totals = card(0xFFF8FAFC, 0xFFE2E8F0, 14);
        partsTotalText = tv("Peças: R$ 0,00", 13, true, C.muted);
        grandTotalText = tv("TOTAL: R$ 0,00", 19, true, C.text);
        totals.addView(partsTotalText); totals.addView(grandTotalText);
        body.addView(totals, full(0,7));

        notesInput = multiline("Observações, recomendação, retorno...");
        body.addView(field("Observação", notesInput), full(0,7));

        Button save = button("Salvar atendimento", C.blue); save.setMinHeight(dp(56)); save.setOnClickListener(v -> saveEditor());
        body.addView(save, full(0,6));

        if (id != null) {
            LinearLayout existingActions = horizontal();
            Button share = button("Enviar relatório", C.green); share.setOnClickListener(v -> shareSingleReport(id));
            Button del = button("Excluir", C.red); del.setOnClickListener(v -> confirmDeleteOne(id));
            existingActions.addView(share, weight(1)); existingActions.addView(del, weight(1));
            body.addView(existingActions, full(0,6));
            loadServiceIntoEditor(id);
        } else {
            dateInput.setText(br(isoDay.format(new Date())));
            addPartRow(null, 1, 0);
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
            serviceInput.setText(c.getString(c.getColumnIndexOrThrow("service_text")));
            laborInput.setText(decimal(c.getDouble(c.getColumnIndexOrThrow("labor_value"))));
            notesInput.setText(c.getString(c.getColumnIndexOrThrow("notes")));
        } finally { c.close(); }
        Cursor p = db.parts(id);
        try {
            while (p.moveToNext()) addPartRow(
                    p.getString(p.getColumnIndexOrThrow("description")),
                    p.getDouble(p.getColumnIndexOrThrow("quantity")),
                    p.getDouble(p.getColumnIndexOrThrow("unit_value")));
        } finally { p.close(); }
        if (partRows.isEmpty()) addPartRow(null,1,0);
        refreshTotals();
    }

    private void addPartRow(String description, double quantity, double unitValue) {
        LinearLayout box = card(Color.WHITE, 0xFFE2E8F0, 14);
        EditText desc = input("Descrição da peça"); if(description != null) desc.setText(description);
        box.addView(field("Peça", desc), full(0,3));
        LinearLayout row = horizontal();
        EditText qty = numberInput("Qtd."); qty.setText(decimalQty(quantity));
        EditText unit = moneyInput("Valor"); if(unitValue > 0) unit.setText(decimal(unitValue));
        Button remove = button("×", C.gray); remove.setTextSize(19);
        row.addView(field("Qtd.", qty), weight(1)); row.addView(field("Valor unit. R$", unit), weight(1)); row.addView(remove, fixed(52));
        box.addView(row, full(0,0));
        TextView subtotal = tv("Subtotal: R$ 0,00", 12, true, C.muted); subtotal.setGravity(Gravity.END); box.addView(subtotal, full(0,0));
        PartRow pr = new PartRow(box, desc, qty, unit, subtotal); partRows.add(pr); partsContainer.addView(box, full(0,5));
        TextWatcher watcher = totalWatcher(); qty.addTextChangedListener(watcher); unit.addTextChangedListener(watcher);
        remove.setOnClickListener(v -> { partsContainer.removeView(box); partRows.remove(pr); refreshTotals(); });
        refreshTotals();
    }

    private TextWatcher totalWatcher() { return new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){ refreshTotals(); } public void afterTextChanged(Editable e){} }; }

    private void refreshTotals() {
        double parts = 0;
        for (PartRow p : partRows) {
            double subtotal = value(p.qty) * value(p.unit);
            parts += subtotal;
            if (p.subtotal != null) p.subtotal.setText("Subtotal: " + money(subtotal));
        }
        double labor = laborInput == null ? 0 : value(laborInput);
        if (partsTotalText != null) partsTotalText.setText("Peças: " + money(parts));
        if (grandTotalText != null) grandTotalText.setText("TOTAL: " + money(parts + labor));
    }

    private void saveEditor() {
        try {
            String dateIso = toIso(dateInput.getText().toString());
            JSONArray parts = new JSONArray();
            for (PartRow p : partRows) {
                String d = p.desc.getText().toString().trim();
                if (d.isEmpty()) continue;
                JSONObject j = new JSONObject(); j.put("description", d); j.put("quantity", value(p.qty)); j.put("unitValue", value(p.unit)); parts.put(j);
            }
            long id = db.saveService(editingId,
                    plateInput.getText().toString(), longValue(kmInput), dateIso,
                    serviceInput.getText().toString(), value(laborInput), notesInput.getText().toString(),
                    parts, nowFmt.format(new Date()));
            toast(editingId == null ? "Atendimento salvo." : "Atendimento atualizado.");
            editingId = id;
            clearEditorState(); showHome();
        } catch (Exception e) { error(e.getMessage()); }
    }

    private void showAssistantDialog() {
        LinearLayout box = dialogBody();
        TextView intro = tv("Consulta somente os dados salvos neste celular. Não usa internet e não inventa informação.", 12, false, C.muted);
        box.addView(intro, full(0,5));
        EditText q = multiline("Ex.: o que foi feito na ABC1D23?\nQuanto deu este mês?\nQuais peças mais usei?");
        q.setMinHeight(dp(92)); box.addView(q, full(0,5));
        TextView answer = tv("", 14, false, C.text); answer.setPadding(dp(12),dp(12),dp(12),dp(12)); answer.setBackground(bg(0xFFF8FAFC,0xFFE2E8F0,12)); answer.setVisibility(View.GONE); box.addView(answer, full(0,4));
        Button ask = button("Consultar", C.blue); box.addView(ask, full(0,0));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("IA local • Piu").setView(box).setNegativeButton("Fechar", null).create();
        ask.setOnClickListener(v -> { String result = db.answerLocalAssistant(q.getText().toString()); answer.setText(result); answer.setVisibility(View.VISIBLE); });
        dialog.show();
    }

    private void showReportsDialog() {
        LinearLayout box = dialogBody();
        Calendar now = Calendar.getInstance(); Calendar first = (Calendar) now.clone(); first.set(Calendar.DAY_OF_MONTH,1);
        EditText start = input("Início"); start.setFocusable(false); start.setText(br(isoDay.format(first.getTime()))); start.setOnClickListener(v -> pickDate(start));
        EditText end = input("Fim"); end.setFocusable(false); end.setText(br(isoDay.format(now.getTime()))); end.setOnClickListener(v -> pickDate(end));
        LinearLayout dates = horizontal(); dates.addView(field("De",start),weight(1)); dates.addView(field("Até",end),weight(1)); box.addView(dates,full(0,5));
        TextView summary = tv("",14,true,C.text); summary.setPadding(dp(12),dp(12),dp(12),dp(12)); summary.setBackground(bg(0xFFF8FAFC,0xFFE2E8F0,12)); box.addView(summary,full(0,5));
        Button update = softButton("Atualizar resumo"); Button pdf = button("Compartilhar relatório PDF", C.blue);
        box.addView(update,full(0,4)); box.addView(pdf,full(0,0));
        Runnable refresh = () -> { try { DatabaseHelper.Summary s=db.summary(toIso(start.getText().toString()),toIso(end.getText().toString())); summary.setText(s.count+" atendimento(s)\nMão de obra: "+money(s.labor)+"\nPeças: "+money(s.parts)+"\nTotal: "+money(s.total)); } catch(Exception e){ summary.setText("Confira as datas."); } };
        update.setOnClickListener(v -> refresh.run()); pdf.setOnClickListener(v -> { try { shareRangeReport(toIso(start.getText().toString()),toIso(end.getText().toString())); } catch(Exception e){ error(e.getMessage()); } });
        refresh.run();
        new AlertDialog.Builder(this).setTitle("Relatórios").setView(box).setNegativeButton("Fechar",null).show();
    }

    private void showBackupDialog() {
        LinearLayout box = dialogBody();
        TextView info = tv("Os atendimentos ficam no celular. O backup JSON permite restaurar tudo em outro aparelho.",12,false,C.muted); box.addView(info,full(0,6));
        Button exp = button("Exportar backup completo", C.blue); exp.setOnClickListener(v -> createDocument("application/json", "backup-controle-motos-piu-"+isoDay.format(new Date())+".json", REQ_EXPORT_BACKUP));
        Button imp = button("Importar / restaurar backup", C.green); imp.setOnClickListener(v -> openDocument());
        Button csv = softButton("Exportar planilha CSV"); csv.setOnClickListener(v -> createDocument("text/csv", "atendimentos-piu-"+isoDay.format(new Date())+".csv", REQ_EXPORT_CSV));
        box.addView(exp,full(0,4)); box.addView(imp,full(0,4)); box.addView(csv,full(0,0));
        new AlertDialog.Builder(this).setTitle("Backup e exportação").setView(box).setNegativeButton("Fechar",null).show();
    }

    private void createDocument(String type, String name, int requestCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(type); i.putExtra(Intent.EXTRA_TITLE,name); startActivityForResult(i,requestCode);
    }
    private void openDocument() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,REQ_IMPORT_BACKUP); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==REQ_EXPORT_BACKUP) exportBackup(uri);
        else if(requestCode==REQ_EXPORT_CSV) exportCsv(uri);
        else if(requestCode==REQ_IMPORT_BACKUP) confirmImport(uri);
    }

    private void exportBackup(Uri uri) {
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new Exception("Não foi possível criar o arquivo.");
            JSONObject root=db.exportAll(); root.put("exportedAt",nowFmt.format(new Date()));
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8)); toast("Backup exportado.");
        }catch(Exception e){error(e.getMessage());}
    }
    private void exportCsv(Uri uri) {
        try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new Exception("Não foi possível criar o arquivo.");
            String csv="\uFEFF"+db.exportCsv(null,null); out.write(csv.getBytes(StandardCharsets.UTF_8)); toast("Planilha CSV exportada.");
        }catch(Exception e){error(e.getMessage());}
    }
    private void confirmImport(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Restaurar backup?").setMessage("O backup substituirá os dados atuais do aplicativo. Faça uma exportação antes se precisar preservar o que já está salvo.").setNegativeButton("Cancelar",null).setPositiveButton("Restaurar",(d,w)->importBackup(uri)).show();
    }
    private void importBackup(Uri uri) {
        try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),StandardCharsets.UTF_8))){
            StringBuilder sb=new StringBuilder();String line;while((line=r.readLine())!=null)sb.append(line);
            db.importAll(new JSONObject(sb.toString()));toast("Backup restaurado com sucesso.");searchText="";showHome();
        }catch(Exception e){error(e.getMessage());}
    }

    private void confirmDeleteOne(long id) {
        new AlertDialog.Builder(this).setTitle("Excluir atendimento?").setMessage("Esta ação não pode ser desfeita.").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->{db.deleteService(id);toast("Atendimento excluído.");clearEditorState();showHome();}).show();
    }
    private void confirmDeleteSelected() {
        if(selectedIds.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Excluir selecionados?").setMessage("Excluir definitivamente "+selectedIds.size()+" atendimento(s)?").setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->{int n=db.deleteServices(new HashSet<>(selectedIds));toast(n+" atendimento(s) excluído(s).");selectedIds.clear();selectionMode=false;showHome();}).show();
    }

    private void shareSingleReport(long id) { try { List<Long> one=new ArrayList<>(); one.add(id); sharePdf(buildReportPdf("Relatório de Atendimento", null, null, one), "relatorio-atendimento-piu.pdf"); } catch(Exception e){error(e.getMessage());} }
    private void shareSelectedReport() { try { sharePdf(buildReportPdf("Relatório de Atendimentos Selecionados", null, null, new ArrayList<>(selectedIds)), "relatorio-selecionados-piu.pdf"); } catch(Exception e){error(e.getMessage());} }
    private void shareRangeReport(String start, String end) { try { sharePdf(buildReportPdf("Relatório de Atendimentos",start,end,null),"relatorio-piu-"+start+"-a-"+end+".pdf"); } catch(Exception e){error(e.getMessage());} }

    private File buildReportPdf(String title, String start, String end, List<Long> ids) throws Exception {
        File dir=new File(getCacheDir(),"reports"); if(!dir.exists()&&!dir.mkdirs())throw new Exception("Não foi possível preparar o relatório.");
        File file=new File(dir,"piu-report-"+System.currentTimeMillis()+".pdf");
        PdfDocument doc=new PdfDocument(); PdfWriter writer=new PdfWriter(doc);
        writer.title(title);
        if(start!=null||end!=null) writer.line("Período: "+(start==null?"início":br(start))+" a "+(end==null?"hoje":br(end)),12,true,C.muted);

        double laborSum=0,partsSum=0; int count=0;
        Cursor c;
        if(ids==null) c=db.services("",start,end); else c=null;
        try {
            if(ids==null) {
                while(c.moveToNext()) {
                    long id=c.getLong(c.getColumnIndexOrThrow("id"));
                    laborSum+=c.getDouble(c.getColumnIndexOrThrow("labor_value"));partsSum+=c.getDouble(c.getColumnIndexOrThrow("parts_total"));count++;
                    writeService(writer,c,id);
                }
            } else {
                for(Long id:ids){Cursor one=db.service(id);try{if(one.moveToFirst()){laborSum+=one.getDouble(one.getColumnIndexOrThrow("labor_value"));partsSum+=one.getDouble(one.getColumnIndexOrThrow("parts_total"));count++;writeService(writer,one,id);}}finally{one.close();}}
            }
        } finally { if(c!=null)c.close(); }
        writer.separator(); writer.line("RESUMO",13,true,C.text); writer.line(count+" atendimento(s)",11,false,C.text); writer.line("Mão de obra: "+money(laborSum),11,false,C.text); writer.line("Peças: "+money(partsSum),11,false,C.text); writer.line("TOTAL: "+money(laborSum+partsSum),14,true,C.text); writer.line("",8,false,C.text); writer.line("Powered by thIAguinho Soluções Digitais",9,false,C.muted);
        writer.finish();
        try(FileOutputStream out=new FileOutputStream(file)){doc.writeTo(out);}finally{doc.close();}
        return file;
    }

    private void writeService(PdfWriter w, Cursor c, long id) {
        double labor=c.getDouble(c.getColumnIndexOrThrow("labor_value")),parts=c.getDouble(c.getColumnIndexOrThrow("parts_total"));
        w.separator();
        w.line(DatabaseHelper.displayPlate(c.getString(c.getColumnIndexOrThrow("plate")))+" • "+formatInt(c.getLong(c.getColumnIndexOrThrow("km")))+" km • "+br(c.getString(c.getColumnIndexOrThrow("service_date"))),13,true,C.text);
        w.line("Serviço: "+c.getString(c.getColumnIndexOrThrow("service_text")),11,false,C.text);
        Cursor p=db.parts(id);try{while(p.moveToNext()){double qty=p.getDouble(p.getColumnIndexOrThrow("quantity"));double unit=p.getDouble(p.getColumnIndexOrThrow("unit_value"));w.line("Peça: "+p.getString(p.getColumnIndexOrThrow("description"))+" • "+decimalQty(qty)+" x "+money(unit)+" = "+money(qty*unit),10,false,C.muted);}}finally{p.close();}
        String notes=c.getString(c.getColumnIndexOrThrow("notes"));if(notes!=null&&!notes.trim().isEmpty())w.line("Observação: "+notes.trim(),10,false,C.muted);
        w.line("Mão de obra: "+money(labor)+" • Peças: "+money(parts)+" • Total: "+money(labor+parts),11,true,C.green);
    }

    private void sharePdf(File file, String subject) {
        Uri uri= FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);
        Intent share=new Intent(Intent.ACTION_SEND);share.setType("application/pdf");share.putExtra(Intent.EXTRA_STREAM,uri);share.putExtra(Intent.EXTRA_SUBJECT,subject);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Enviar relatório"));
    }

    private class PdfWriter {
        final PdfDocument doc; PdfDocument.Page page; Canvas canvas; int pageNo=0; float y; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); final int width=595,height=842,margin=42;
        PdfWriter(PdfDocument d){doc=d;newPage();}
        void newPage(){if(page!=null)doc.finishPage(page);PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(width,height,++pageNo).create();page=doc.startPage(info);canvas=page.getCanvas();canvas.drawColor(Color.WHITE);y=margin;}
        void title(String s){line(s,20,true,C.text);line("Controle de Motos • Piu",10,false,C.muted);separator();}
        void separator(){ensure(18);paint.setColor(0xFFE2E8F0);paint.setStrokeWidth(1);canvas.drawLine(margin,y,width-margin,y,paint);y+=12;}
        void line(String text,float size,boolean bold,int color){if(text==null)text="";paint.setTextSize(size);paint.setTypeface(bold?Typeface.create(Typeface.DEFAULT,Typeface.BOLD):Typeface.DEFAULT);paint.setColor(color);float max=width-margin*2;for(String paragraph:text.split("\\n",-1)){List<String> lines=wrapText(paragraph,paint,max);if(lines.isEmpty())lines.add("");for(String l:lines){ensure(size+8);canvas.drawText(l,margin,y,paint);y+=size+5;}}}
        void ensure(float need){if(y+need>height-margin)newPage();}
        List<String> wrapText(String text,Paint p,float max){List<String> out=new ArrayList<>();if(text.isEmpty()){out.add("");return out;}String[] words=text.split("\\s+");StringBuilder line=new StringBuilder();for(String word:words){String test=line.length()==0?word:line+" "+word;if(p.measureText(test)>max&&line.length()>0){out.add(line.toString());line=new StringBuilder(word);}else{if(line.length()>0)line.append(' ');line.append(word);}}if(line.length()>0)out.add(line.toString());return out;}
        void finish(){if(page!=null){doc.finishPage(page);page=null;}}
    }

    private LinearLayout basePage(String title, String subtitle, boolean back) {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(C.bg);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(14),dp(13),dp(14),dp(13));header.setBackgroundColor(C.dark);
        if(back){Button b=button("‹",0xFF374151);b.setTextSize(24);b.setOnClickListener(v->{clearEditorState();showHome();});header.addView(b,fixed(48));}
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);TextView t=tv(title,19,true,Color.WHITE);TextView s=tv(subtitle,11,false,0xFFCBD5E1);names.addView(t);names.addView(s);header.addView(names,weight(1));
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=new LinearLayout(this);body.setTag("BODY");body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(12),dp(12),dp(12),dp(16));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));return root;
    }
    private LinearLayout bodyOf(LinearLayout root){ScrollView s=(ScrollView)root.getChildAt(1);return (LinearLayout)s.getChildAt(0);}
    private void footer(LinearLayout body){TextView f=tv("Powered by thIAguinho Soluções Digitais",9,false,0xFF94A3B8);f.setGravity(Gravity.CENTER);f.setPadding(dp(8),dp(18),dp(8),dp(14));body.addView(f,full(0,0));}

    private LinearLayout dialogBody(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(18),dp(4),dp(18),dp(4));return b;}
    private LinearLayout card(int fill,int stroke,int radius){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setBackground(bg(fill,stroke,radius));v.setElevation(dp(1));return v;}
    private LinearLayout field(String label,View input){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);TextView l=tv(label,11,true,C.muted);l.setPadding(dp(2),0,0,dp(3));b.addView(l);b.addView(input,new LinearLayout.LayoutParams(-1,-2));return b;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setSingleLine(true);e.setTextColor(C.text);e.setHintTextColor(0xFF94A3B8);e.setBackground(bg(Color.WHITE,0xFFCBD5E1,12));e.setPadding(dp(11),0,dp(11),0);e.setMinHeight(dp(48));return e;}
    private EditText multiline(String hint){EditText e=input(hint);e.setSingleLine(false);e.setGravity(Gravity.TOP|Gravity.START);e.setPadding(dp(11),dp(11),dp(11),dp(11));e.setMinHeight(dp(78));e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);return e;}
    private EditText numberInput(String hint){EditText e=input(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);return e;}
    private EditText moneyInput(String hint){return numberInput(hint);}
    private Button button(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setPadding(dp(7),0,dp(7),0);b.setMinHeight(dp(46));b.setBackground(bg(color,color,12));return b;}
    private Button softButton(String text){Button b=button(text,0xFFF8FAFC);b.setTextColor(C.text);b.setBackground(bg(0xFFF8FAFC,0xFFE2E8F0,12));return b;}
    private TextView tv(String text,int size,boolean bold,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(dp(3),dp(3),dp(3),dp(3));return t;}
    private GradientDrawable bg(int fill,int stroke,int radius){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams full(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private LinearLayout.LayoutParams fullNoMargin(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams weight(float w){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,w);p.setMargins(dp(3),dp(2),dp(3),dp(2));return p;}
    private LinearLayout.LayoutParams fixed(int w){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(w),-2);p.setMargins(dp(3),dp(2),dp(3),dp(2));return p;}
    private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-2,-2);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private void pickDate(EditText target){Calendar cal=Calendar.getInstance();try{Date d=brDay.parse(target.getText().toString());if(d!=null)cal.setTime(d);}catch(Exception ignored){}new DatePickerDialog(this,(view,y,m,d)->target.setText(String.format(BR,"%02d/%02d/%04d",d,m+1,y)),cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();}
    private double value(EditText e){
        try{
            String s=e.getText().toString().trim().replace(" ","");
            if(s.isEmpty())return 0;
            if(s.contains(",")&&s.contains(".")) s=s.replace(".","").replace(',','.');
            else if(s.contains(",")) s=s.replace(',','.');
            return Double.parseDouble(s);
        }catch(Exception x){return 0;}
    }
    private long longValue(EditText e){try{return Long.parseLong(e.getText().toString().replaceAll("[^0-9]",""));}catch(Exception x){return 0;}}
    private String toIso(String br) throws Exception {Date d=brDay.parse(br);if(d==null)throw new Exception("Data inválida.");return isoDay.format(d);}
    private String br(String iso){try{Date d=isoDay.parse(iso);return d==null?iso:brDay.format(d);}catch(Exception e){return iso;}}
    private String money(double v){return String.format(BR,"R$ %,.2f",v);}
    private String decimal(double v){return String.format(BR,"%.2f",v);}
    private String decimalQty(double v){return Math.abs(v-Math.rint(v))<0.000001?String.valueOf((long)Math.rint(v)):String.format(BR,"%.2f",v);}
    private String formatInt(long v){return String.format(BR,"%,d",v);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void error(String s){new AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(s==null?"Erro inesperado.":s).setPositiveButton("OK",null).show();}
    private void clearEditorState(){editingId=null;plateInput=null;kmInput=null;dateInput=null;serviceInput=null;laborInput=null;notesInput=null;partsContainer=null;partsTotalText=null;grandTotalText=null;partRows.clear();}

    private static class PartRow { final LinearLayout root; final EditText desc,qty,unit; final TextView subtotal; PartRow(LinearLayout r,EditText d,EditText q,EditText u,TextView s){root=r;desc=d;qty=q;unit=u;subtotal=s;} }
    private static class C { static final int bg=0xFFF3F4F6,text=0xFF111827,muted=0xFF64748B,dark=0xFF111827,blue=0xFF2563EB,green=0xFF16A34A,red=0xFFDC2626,gray=0xFF475569; }
}
