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
  if (payload.action === "update_transaction") {
    return ContentService.createTextOutput(JSON.stringify(updateTransaction(payload)))
      .setMimeType(ContentService.MimeType.JSON);
  }
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

  const usesSpillCat = hasSpillCategoryFormula(sh);
  if (usesSpillCat) {
    sh.appendRow([
      payload.date || "",
      payload.description || "",
      payload.debit != null ? payload.debit : "",
      payload.credit != null ? payload.credit : "",
      payload.balance != null ? payload.balance : ""
    ]);
    if (payload.category) {
      sh.getRange(sh.getLastRow(), 8).setValue(String(payload.category).trim());
    }
  } else {
    sh.appendRow([
      payload.date || "",
      payload.description || "",
      payload.debit != null ? payload.debit : "",
      payload.credit != null ? payload.credit : "",
      payload.balance != null ? payload.balance : "",
      payload.category || ""
    ]);
  }

  return ContentService.createTextOutput(JSON.stringify({ ok: true, message: "Added", balance: payload.balance }))
    .setMimeType(ContentService.MimeType.JSON);
}

function hasSpillCategoryFormula(sh) {
  const f2 = String(sh.getRange(2, 6).getFormula() || "").trim();
  return /^=\s*(MAP|ARRAYFORMULA)\b/i.test(f2);
}

function updateTransaction(payload) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sh = ss.getSheetByName(payload.sheetName || "Debit Card");
  if (!sh) return { ok: false, error: "Sheet not found" };
  const lastRow = sh.getLastRow();
  if (lastRow < 2) return { ok: false, error: "No rows to update" };

  const targetDate = String(payload.dateIso || "").trim();
  const targetDebit = Number(payload.debit || 0);
  const targetCredit = Number(payload.credit || 0);
  const targetBalance = Number(payload.balance || 0);
  const newDesc = String(payload.newDescription || "").trim();
  const newCat = String(payload.newCategory || "").trim();
  const tz = Session.getScriptTimeZone();
  const usesSpillCat = hasSpillCategoryFormula(sh);

  const numCols = Math.max(sh.getLastColumn(), 6);
  const values = sh.getRange(2, 1, lastRow - 1, numCols).getValues();
  let updatedCount = 0;

  for (let i = 0; i < values.length; i++) {
    const row = values[i];
    let rowDate = "";
    if (row[0] instanceof Date) {
      rowDate = Utilities.formatDate(row[0], tz, "yyyy-MM-dd");
    } else {
      const s = String(row[0] || "").trim();
      const m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
      rowDate = m ? (m[3] + "-" + m[1].padStart(2, "0") + "-" + m[2].padStart(2, "0")) : s.slice(0, 10);
    }
    const rDeb = Number(String(row[2] || "0").replace(/,/g, "")) || 0;
    const rCred = Number(String(row[3] || "0").replace(/,/g, "")) || 0;
    const rBal = Number(String(row[4] || "0").replace(/,/g, "")) || 0;

    if (
      rowDate === targetDate &&
      Math.abs(rDeb - targetDebit) < 0.01 &&
      Math.abs(rCred - targetCredit) < 0.01 &&
      Math.abs(rBal - targetBalance) < 0.01
    ) {
      const sheetRowNum = i + 2;
      if (newDesc) sh.getRange(sheetRowNum, 2).setValue(newDesc);
      if (newCat) sh.getRange(sheetRowNum, usesSpillCat ? 8 : 6).setValue(newCat);
      updatedCount++;
    }
  }
  if (updatedCount === 0) return { ok: false, error: "Matching transaction row not found" };
  return { ok: true, updatedRows: updatedCount, message: "Updated " + updatedCount + " row(s) in Google Sheets" };
}
```
