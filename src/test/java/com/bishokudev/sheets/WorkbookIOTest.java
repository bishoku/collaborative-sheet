package com.bishokudev.sheets;
import org.junit.Test;
import org.json.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class WorkbookIOTest {
    @Test public void roundTripPreservesValuesColorsMerges() throws Exception {
        byte[] input;
        try(XSSFWorkbook b=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet s=b.createSheet("Example");Cell c=s.createRow(0).createCell(0);c.setCellValue("Başlık");
            XSSFCellStyle style=b.createCellStyle();style.setFillPattern(FillPatternType.SOLID_FOREGROUND);style.setFillForegroundColor(new XSSFColor(new byte[]{(byte)255,0,0},new DefaultIndexedColorMap()));c.setCellStyle(style);
            s.addMergedRegion(new CellRangeAddress(0,1,0,2));s.createRow(2).createCell(0).setCellValue(42.5);b.write(out);input=out.toByteArray();
        }
        JSONObject d=WorkbookIO.read(input,"xlsx").getJSONArray("sheets").getJSONObject(0).getJSONObject("document");
        try(XSSFWorkbook b=new XSSFWorkbook(new ByteArrayInputStream(WorkbookIO.write(d,false)))){
            assertEquals("Başlık",b.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());assertEquals(1,b.getSheetAt(0).getNumMergedRegions());
            assertEquals("A1:C2",b.getSheetAt(0).getMergedRegion(0).formatAsString());assertEquals(42.5,b.getSheetAt(0).getRow(2).getCell(0).getNumericCellValue(),0);
            assertEquals("FFFF0000",((XSSFCellStyle)b.getSheetAt(0).getRow(0).getCell(0).getCellStyle()).getFillForegroundXSSFColor().getARGBHex());
        }
    }
    @Test public void csvHandlesEmbeddedNewlinesQuotesAndTrailingEmpty(){java.util.List<java.util.List<String>> rows=WorkbookIO.parseCsv("\"a,b\",\"line1\nline2\",\"a\"\"b\",\r\n");assertEquals(1,rows.size());assertEquals(4,rows.get(0).size());assertEquals("line1\nline2",rows.get(0).get(1));assertEquals("a\"b",rows.get(0).get(2));assertEquals("",rows.get(0).get(3));}
    @Test(expected=IllegalArgumentException.class) public void brokenCsvRejected(){WorkbookIO.parseCsv("\"unclosed");}
    @Test public void csvFormulaTextIsNotExecuted() throws Exception {JSONObject d=WorkbookIO.read("=1+1".getBytes(StandardCharsets.UTF_8),"csv").getJSONArray("sheets").getJSONObject(0).getJSONObject("document");assertTrue(new String(WorkbookIO.write(d,true),StandardCharsets.UTF_8).contains("'=1+1"));try(XSSFWorkbook b=new XSSFWorkbook(new ByteArrayInputStream(WorkbookIO.write(d,false)))){assertEquals(CellType.STRING,b.getSheetAt(0).getRow(0).getCell(0).getCellType());}}
    @Test(expected=IllegalArgumentException.class) public void formulaImportFailsExplicitly() throws Exception {try(XSSFWorkbook b=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){b.createSheet().createRow(0).createCell(0).setCellFormula("1+1");b.write(out);WorkbookIO.read(out.toByteArray(),"xlsx");}}
    @Test public void macDateSystemPreserved() throws Exception {
        try(XSSFWorkbook b=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            b.getCTWorkbook().getWorkbookPr().setDate1904(true);b.createSheet().createRow(0).createCell(0).setCellValue(45000);b.write(out);
            JSONObject d=WorkbookIO.read(out.toByteArray(),"xlsx").getJSONArray("sheets").getJSONObject(0).getJSONObject("document");
            try(XSSFWorkbook exported=new XSSFWorkbook(new ByteArrayInputStream(WorkbookIO.write(d,false)))){assertTrue(exported.isDate1904());assertEquals(45000,exported.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue(),0);}
        }
    }

    @Test public void dimensionsAndTextStylesRoundTrip() throws Exception {
        JSONObject d=SheetEngine.empty();String r=d.getJSONArray("rows").getString(0),c=d.getJSONArray("columns").getString(0);
        d.put("rowHeights",new JSONObject().put(r,64)).put("columnWidths",new JSONObject().put(c,180));
        d.getJSONObject("cells").put(r+":"+c,new JSONObject().put("value","Line one\nLine two").put("type","text").put("bold",true).put("italic",true).put("underline",true).put("wrap",true).put("fontSize",18).put("align","center"));
        byte[] bytes=WorkbookIO.write(d,false);
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            Sheet sheet=book.getSheetAt(0);assertEquals(48,sheet.getRow(0).getHeightInPoints(),.1);assertEquals(180,sheet.getColumnWidthInPixels(0),2);
            XSSFCellStyle style=(XSSFCellStyle)sheet.getRow(0).getCell(0).getCellStyle();assertTrue(style.getFont().getBold());assertTrue(style.getFont().getItalic());assertEquals(Font.U_SINGLE,style.getFont().getUnderline());assertEquals(18,style.getFont().getFontHeightInPoints());assertTrue(style.getWrapText());assertEquals(HorizontalAlignment.CENTER,style.getAlignment());
        }
        JSONObject imported=WorkbookIO.read(bytes,"xlsx").getJSONArray("sheets").getJSONObject(0).getJSONObject("document");String nr=imported.getJSONArray("rows").getString(0),nc=imported.getJSONArray("columns").getString(0);assertEquals(64,imported.getJSONObject("rowHeights").getInt(nr));assertEquals(180,imported.getJSONObject("columnWidths").getInt(nc),2);assertEquals("Line one\nLine two",imported.getJSONObject("cells").getJSONObject(nr+":"+nc).getString("value"));
    }
}
