# Controle de Motos — Piu

APK Android nativo, offline e sem Firebase para registrar as motos atendidas pelo Piu.

## Objetivo

Uso rápido no celular, com poucas telas e sem cadastro burocrático. Os dados ficam no próprio aparelho em banco SQLite e o aplicativo não solicita permissão de internet.

## Funções

- Novo atendimento com placa, KM, data, serviço, peças, valores e observação.
- Quantas peças forem necessárias por atendimento.
- Cálculo automático de peças, mão de obra e total.
- Cards com placa visual Mercosul e KM em destaque.
- Busca por placa, serviço, peça ou observação.
- Histórico completo por placa.
- Edição de atendimento.
- Exclusão individual.
- Seleção de vários atendimentos e exclusão em lote.
- Relatório individual em PDF para compartilhar pelo Android/WhatsApp.
- Relatório por período em PDF.
- Relatório dos atendimentos selecionados.
- Exportação CSV para planilha.
- Backup completo JSON.
- Restauração completa de backup JSON.
- IA local para consultar as informações lançadas no aparelho.

## IA local

A IA local não usa internet, servidor nem API externa. Ela interpreta perguntas e consulta exclusivamente o banco local do aparelho.

Exemplos:

- `O que foi feito na ABC1D23?`
- `Qual o último KM da ABC1D23?`
- `Quanto deu este mês?`
- `Quais peças mais usei?`
- `Qual foi o atendimento mais caro?`
- `Mostre atendimentos com pastilha`
- `Procure observação vazamento`
- `Resumo de hoje`

Ela também faz busca livre nos campos placa, serviço, peças e observações. Se não existir informação lançada que sustente a resposta, informa que não encontrou.

## Banco de dados

SQLite local no Android. É gratuito e fica embutido no aplicativo. Não há Firebase.

O banco continua salvo ao fechar o app. Como qualquer dado local, ele pode ser perdido se o aplicativo for desinstalado ou o aparelho for apagado. Por isso existe o botão **Backup**.

## Como subir em um repositório novo pelo celular

1. Crie um repositório vazio no GitHub.
2. Extraia este ZIP.
3. Envie **todo o conteúdo de dentro da pasta `Controle-Motos-Piu` para a raiz do repositório**.
4. Confirme que `.github/workflows/android-apk.yml` foi enviado.
5. Abra a aba **Actions** do repositório.
6. Entre em **Gerar APK Android - Controle de Motos Piu**.
7. Toque em **Run workflow**.
8. Ao terminar, abra o build e baixe o artifact **CONTROLE-MOTOS-PIU-APK**.
9. Extraia o artifact e instale `app-debug.apk` no Android.

## Estrutura principal

```text
.github/workflows/android-apk.yml
app/
  build.gradle
  src/main/AndroidManifest.xml
  src/main/java/com/thiaguinho/controlemotospiu/
    MainActivity.java
    DatabaseHelper.java
  src/main/res/
build.gradle
gradle.properties
settings.gradle
README.md
```

## Observação sobre assinatura

O GitHub Actions gera APK `debug`, adequado para instalação direta e testes internos. Não foi reaproveitada a chave privada de assinatura do aplicativo do bar.

Powered by thIAguinho Soluções Digitais
