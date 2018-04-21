package com.github.takezoe.xlsbeans.processor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.takezoe.xlsbeans.NeedPostProcess;
import com.github.takezoe.xlsbeans.Utils;
import com.github.takezoe.xlsbeans.XLSBeansConfig;
import com.github.takezoe.xlsbeans.XLSBeansException;
import com.github.takezoe.xlsbeans.annotation.*;
import com.github.takezoe.xlsbeans.xml.AnnotationReader;
import com.github.takezoe.xlsbeans.xssfconverter.*;

/**
 * The {@link FieldProcessor}
 * implementation for {@link VerticalRecords}.
 *
 * @author Naoki Takezoe
 * @see VerticalRecords
 */
public class VerticalRecordsProcessor implements FieldProcessor {

  public void doProcess(WSheet wSheet, Object obj, Method setter, Annotation ann, AnnotationReader reader,
                        XLSBeansConfig config, List<NeedPostProcess> needPostProcess) throws Exception {

    VerticalRecords records = (VerticalRecords) ann;

    Class<?>[] clazzes = setter.getParameterTypes();
    if (clazzes.length != 1) {
      throw new XLSBeansException("Arguments of '" + setter.toString() + "' is invalid.");
    } else if (List.class.isAssignableFrom(clazzes[0])) {
      Class<?> recordClass = records.recordClass();
      if (recordClass == Object.class) {
        ParameterizedType t = (ParameterizedType) setter.getGenericParameterTypes()[0];
        recordClass = (Class<?>) t.getActualTypeArguments()[0];
      }
      List<?> value = createRecords(wSheet, records, recordClass, reader, config, needPostProcess);
      if (value != null) {
        setter.invoke(obj, new Object[]{value});
      }
    } else if (clazzes[0].isArray()) {
      Class<?> recordClass = records.recordClass();
      if (recordClass == Object.class) {
        recordClass = clazzes[0].getComponentType();
      }
      List<?> value = createRecords(wSheet, records, recordClass, reader, config, needPostProcess);
      if (value != null) {
        Object array = Array.newInstance(recordClass, value.size());
        for (int i = 0; i < value.size(); i++) {
          Array.set(array, i, value.get(i));
        }
        setter.invoke(obj, new Object[]{array});
      }
    } else {
      throw new XLSBeansException("Arguments of '" + setter.toString() + "' is invalid.");
    }
  }

  public void doProcess(WSheet wSheet, Object obj, Field field, Annotation ann, AnnotationReader reader,
                        XLSBeansConfig config, List<NeedPostProcess> needPostProcess) throws Exception {

    VerticalRecords records = (VerticalRecords) ann;

    Class<?> clazz = field.getType();
    if (List.class.isAssignableFrom(clazz)) {
      Class<?> recordClass = records.recordClass();
      if (recordClass == Object.class) {
        ParameterizedType t = (ParameterizedType) field.getGenericType();
        recordClass = (Class<?>) t.getActualTypeArguments()[0];
      }
      List<?> value = createRecords(wSheet, records, recordClass, reader, config, needPostProcess);
      if (value != null) {
        field.set(obj, value);
      }
    } else if (clazz.isArray()) {
      Class<?> recordClass = records.recordClass();
      if (recordClass == Object.class) {
        recordClass = clazz.getComponentType();
      }
      List<?> value = createRecords(wSheet, records, recordClass, reader, config, needPostProcess);
      if (value != null) {
        Object array = Array.newInstance(recordClass, value.size());
        for (int i = 0; i < value.size(); i++) {
          Array.set(array, i, value.get(i));
        }
        field.set(obj, array);
      }
    } else {
      throw new XLSBeansException("Arguments of '" + field.toString() + "' is invalid.");
    }
  }

  private List<?> createRecords(WSheet wSheet, VerticalRecords records, Class<?> recordClass, AnnotationReader reader,
                                XLSBeansConfig config, List<NeedPostProcess> needPostProcess) throws Exception {
    List<Object> result = new ArrayList<Object>();
    List<HeaderInfo> headers = new ArrayList<HeaderInfo>();

    // get header
    int initColumn = -1;
    int initRow = -1;

    if (records.tableLabel().equals("")) {
      initColumn = records.headerColumn();
      initRow = records.headerRow();
    } else {
      try {
        WCell labelCell = Utils.getCell(wSheet, records.tableLabel(), 0, config);
        initColumn = labelCell.getColumn() + 1;
        initRow = labelCell.getRow();
      } catch (XLSBeansException ex) {
        if (records.optional()) {
          return null;
        } else {
          throw ex;
        }
      }
    }

    int hColumn = initColumn;
    int hRow = initRow;
    int rangeCount = 1;

    while (true) {
      try {
        WCell cell = wSheet.getCell(hColumn, hRow);
        while (cell.getContents().equals("") && rangeCount < records.range()) {
          cell = wSheet.getCell(hColumn, hRow + rangeCount);
          rangeCount++;
        }
        if (cell.getContents().equals("")) {
          break;
        } else {
          for (int j = hColumn; j > initColumn; j--) {
            WCell tmpCell = wSheet.getCell(j, hRow);
            if (!tmpCell.getContents().equals("")) {
              cell = tmpCell;
              break;
            }
          }
        }
        headers.add(new HeaderInfo(cell.getContents(), rangeCount - 1));
        hRow = hRow + rangeCount;
        rangeCount = 1;
      } catch (ArrayIndexOutOfBoundsException ex) {
        break;
      }
      if (records.headerLimit() > 0 && headers.size() >= records.headerLimit()) {
        break;
      }
    }

    // Check for columns
    RecordsProcessorUtil.checkColumns(recordClass, headers, reader, config);

    RecordTerminal terminal = records.terminal();
    if (terminal == null) {
      terminal = RecordTerminal.Empty;
    }

    // get records
    hColumn++;
    while (hColumn < wSheet.getColumns()) {
      hRow = initRow;
      boolean emptyFlag = true;
      Object record = recordClass.newInstance();
      processMapColumns(wSheet, headers, hRow, hColumn, record, reader, config);

      for (int i = 0; i < headers.size() && hColumn < wSheet.getColumns(); i++) {
        HeaderInfo headerInfo = headers.get(i);
        hRow = hRow + headerInfo.getHeaderRange();
        WCell cell = wSheet.getCell(hColumn, hRow);

        // find end of the table
        if (!cell.getContents().equals("")) {
          emptyFlag = false;
        }
        if (terminal == RecordTerminal.Border && i == 0) {
          WCellFormat format = cell.getCellFormat();
          if (format != null && !format.getBorder(WBorder.TOP).equals(WBorderLineStyle.NONE)) {
            emptyFlag = false;
          } else {
            emptyFlag = true;
            break;
          }
        }
        if (!records.terminateLabel().equals("")) {
          if (Utils.matches(cell.getContents(), records.terminateLabel(), config)) {
            emptyFlag = true;
            break;
          }
        }

        List<Object> properties = Utils.getColumnProperties(record, headerInfo.getHeaderLabel(), reader, config);
        for (Object property : properties) {
          WCell valueCell = cell;

          Column column = null;
          if (property instanceof Method) {
            column = reader.getAnnotation(record.getClass(), (Method) property, Column.class);
          } else if (property instanceof Field) {
            column = reader.getAnnotation(record.getClass(), (Field) property, Column.class);
          }

          if (column.headerMerged() > 0) {
            hRow = hRow + column.headerMerged();
            valueCell = wSheet.getCell(hColumn, hRow);
          }
          if (valueCell.getContents().equals("")) {
            WCellFormat valueCellFormat = valueCell.getCellFormat();
            if (column.merged() && (valueCellFormat == null && valueCellFormat.getBorder(WBorder.RIGHT).equals(WBorderLineStyle.NONE))) {
              for (int k = hColumn; k > initColumn; k--) {
                WCell tmpCell = wSheet.getCell(k, hRow);
                WCellFormat tmpCellFormat = tmpCell.getCellFormat();
                if (tmpCellFormat != null && !tmpCellFormat.getBorder(WBorder.LEFT).equals(WBorderLineStyle.NONE)) {
                  break;
                }
                if (!tmpCell.getContents().equals("")) {
                  valueCell = tmpCell;
                  break;
                }
              }
            }
          }
          if (column.headerMerged() > 0) {
            hRow = hRow - column.headerMerged();
          }

          if (property instanceof Method) {
            Utils.setPosition(valueCell.getColumn(), valueCell.getRow(), record, Utils.toPropertyName(((Method) property).getName()));
            Utils.invokeSetter((Method) property, record, valueCell.getContents(), config);
          } else if (property instanceof Field) {
            Utils.setPosition(valueCell.getColumn(), valueCell.getRow(), record, ((Field) property).getName());
            Utils.setField((Field) property, record, valueCell.getContents(), config);
          }
        }
        hRow++;
      }
      if (emptyFlag) {
        break;
      }
      result.add(record);
      for (Method method : record.getClass().getMethods()) {
        PostProcess ann = reader.getAnnotation(record.getClass(), method, PostProcess.class);
        if (ann != null) {
          needPostProcess.add(new NeedPostProcess(record, method));
        }
      }
      hColumn++;
    }

    return result;
  }

  private void processMapColumns(WSheet sheet, List<HeaderInfo> headerInfos,
                                 int begin, int column, Object record, AnnotationReader reader, XLSBeansConfig config) throws Exception {

    List<Object> properties = Utils.getMapColumnProperties(record, reader);

    for (Object property : properties) {
      MapColumns ann = null;
      if (property instanceof Method) {
        ann = reader.getAnnotation(record.getClass(), (Method) property, MapColumns.class);
      } else if (property instanceof Field) {
        ann = reader.getAnnotation(record.getClass(), (Field) property, MapColumns.class);
      }

      boolean flag = false;
      Map<String, String> map = new LinkedHashMap<String, String>();
      for (HeaderInfo headerInfo : headerInfos) {
        if (Utils.matches(headerInfo.getHeaderLabel(), ann.previousColumnName(), config)) {
          flag = true;
          begin++;
          continue;
        }
        if (flag) {
          WCell cell = sheet.getCell(column, begin + headerInfo.getHeaderRange());
          map.put(headerInfo.getHeaderLabel(), cell.getContents());
        }
        begin = begin + headerInfo.getHeaderRange() + 1;
      }

      if (property instanceof Method) {
        ((Method) property).invoke(record, map);
      } else if (property instanceof Field) {
        ((Field) property).set(record, map);
      }
    }
  }

}
