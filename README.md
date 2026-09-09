# Piu • Controle de Motos — Android nativo/offline

Aplicativo Android nativo para controlar atendimentos de motos do Piu. Os dados ficam no próprio celular em SQLite, sem Firebase e sem depender de internet.

## Versão 1.1.0

- Interface mobile revisada e mais profissional.
- Correção de área segura da barra de status do Android.
- Botão **Voltar** em todas as áreas secundárias.
- Novo ícone/identidade com moto + ferramenta.
- Placa Mercosul nos cards, seguindo o padrão visual do cliente.html do SaaS Oficina.
- Um atendimento aceita **vários serviços separados**, cada um com descrição e valor.
- Um atendimento aceita **várias peças**, com quantidade, valor unitário e subtotal.
- Totais automáticos separados: Serviços, Peças e Total Geral.
- Cards mostram data, total, quantidade de serviços e peças e ações rápidas.
- IA local consulta placa, KM, datas, serviços, peças, valores, observações e períodos.
- Relatório PDF redesenhado com placa Mercosul, blocos de serviços/peças, totais e identidade visual.
- Relatório por período em modo **detalhado** ou **resumido**.
- PDF individual para compartilhar pelo Android/WhatsApp.
- Seleção múltipla para relatório e exclusão.
- Backup JSON completo e restauração.
- Importa backups antigos da versão 1.0 e converte o serviço único para a nova estrutura de múltiplos serviços.
- Exportação CSV.

## Dados

Banco local: `controle_motos_piu.db`.

Tabelas principais:
- `services`: atendimento, placa, KM, data e observação.
- `service_items`: serviços realizados e seus valores.
- `parts`: peças, quantidade e valores.

## Gerar o APK no GitHub

1. Envie o conteúdo deste ZIP para a raiz de um repositório novo.
2. Abra **Actions** no GitHub.
3. Execute **Gerar APK Android - Controle de Motos Piu**.
4. Baixe o artifact **CONTROLE-MOTOS-PIU-APK**.

O workflow também roda auditoria Lint e publica os logs de diagnóstico.

Powered by thIAguinho Soluções Digitais
