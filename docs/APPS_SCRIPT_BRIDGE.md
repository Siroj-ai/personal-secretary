# Day Book Google Apps Script Bridge (Idempotent Version)

Your **Personal Secretary** app works directly with the same Google Apps Script Web App schema used by `DayBookTracker` (`?action=bootstrap` and `POST` `add_transaction`).

To prevent duplicate rows from ever accumulating again, you can deploy or update your `Day Book` Apps Script with the idempotent snippet below:

```javascript
function doGet(e) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheets = [
    "Debit Card",
    "Credit Card",
    "Social Security Fund",
    "Earnings vs Salary",
    "Abbreviations"
  ];
  const data = {};
  sheets.forEach((name) => {
    const sh = ss.getSheetByName(name);
    if (sh) {
      data[name] = sh.getDataRange().getDisplayValues();
    }
  });
  return ContentService.createTextOutput(JSON.stringify({ ok: true, data }))
    .setMimeType(ContentService.MimeType.JSON);
}

function doPost(e) {
  const payload = JSON.parse(e.postData.contents || "{}");
  if (payload.action !== "add_transaction") {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "Unsupported action" }))
      .setMimeType(ContentService.MimeType.JSON);
  }
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sh = ss.getSheetByName(payload.sheetName || "Debit Card");
  if (!sh) {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: "Sheet not found" }))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // Idempotency guard: avoid duplicate append if identical last row exists
  const lastRow = sh.getLastRow();
  if (lastRow >= 2) {
    const prev = sh.getRange(lastRow, 1, 1, 5).getDisplayValues()[0];
    if (
      String(prev[0]).trim() === String(payload.date || "").trim() &&
      String(prev[1]).trim() === String(payload.description || "").trim() &&
      String(prev[4]).trim() === String(payload.balance || "").trim()
    ) {
      return ContentService.createTextOutput(JSON.stringify({ ok: true, message: "Duplicate skipped" }))
        .setMimeType(ContentService.MimeType.JSON);
    }
  }

  sh.appendRow([
    payload.date || "",
    payload.description || "",
    payload.debit != null ? payload.debit : "",
    payload.credit != null ? payload.credit : "",
    payload.balance != null ? payload.balance : "",
    payload.category || ""
  ]);

  return ContentService.createTextOutput(JSON.stringify({ ok: true, message: "Added", balance: payload.balance }))
    .setMimeType(ContentService.MimeType.JSON);
}
```
