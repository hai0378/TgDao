package com.tg.dao.processor;

import com.sun.tools.javac.code.Symbol;
import com.tg.dao.exception.TgDaoException;
import com.tg.dao.generator.model.TableMapping;
import com.tg.dao.generator.sql.SqlGen;
import com.tg.dao.annotation.*;
import com.tg.dao.generator.sql.primary.*;
import com.tg.dao.util.StringUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by twogoods on 2017/7/28.
 */
@SupportedAnnotationTypes({"com.tg.dao.annotation.Table", "com.tg.dao.annotation.DaoGen"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions({"com.tg.dao.annotation.Table", "com.tg.dao.annotation.DaoGen"})
public class TgDaoGenerateProcessor extends AbstractProcessor {

    public static final String DAOGEN_ANNOTATION_NAME = DaoGen.class.getCanonicalName();
    public static final String TABLE_ANNOTATION_NAME = Table.class.getCanonicalName();

    private Map<String, TableMapping> nameModelMapping = new HashMap<>();
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        annotations.stream()
                .filter(typeElement -> typeElement.toString().equals(TABLE_ANNOTATION_NAME))
                .forEach(typeElement -> roundEnv.getElementsAnnotatedWith(typeElement).forEach((this::handleTableElement)));
        if (nameModelMapping.size() == 0) {
            messager.printMessage(Diagnostic.Kind.WARNING, "can't find any @Table");
            return true;
        }
        try {
            annotations.stream()
                    .filter(typeElement -> typeElement.toString().equals(DAOGEN_ANNOTATION_NAME))
                    .forEach(typeElement -> roundEnv.getElementsAnnotatedWith(typeElement).forEach((this::handleDaoGenElement)));
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.WARNING, e.toString());
        }
        return true;
    }

    private void handleTableElement(Element element) {
        Symbol.ClassSymbol classSymbol = (Symbol.ClassSymbol) element;
        TableMapping tableMapping = new TableMapping();
        tableMapping.setClassName(classSymbol.getQualifiedName().toString());
        Table table = classSymbol.getAnnotation(Table.class);
        String tableName = table.name();
        tableMapping.setTableName(tableName);

        Map<String, String> fieldToColumn = new HashMap<>();
        Map<String, String> columnToField = new HashMap<>();
        List<Symbol> symbols = classSymbol.getEnclosedElements();
        symbols.stream().filter(symbol -> symbol.getKind() == ElementKind.FIELD)
                .filter(symbol -> symbol.getAnnotation(Ignore.class) == null)
                .forEach(symbol -> {
                    Id id = symbol.getAnnotation(Id.class);
                    if (id != null) {
                        tableMapping.setIdColumn(StringUtils.isEmpty(id.value()) ? symbol.getSimpleName().toString() : id.value());
                        tableMapping.setIdField(symbol.getSimpleName().toString());
                    } else {
                        String columnName = parseColumnAnnotation(symbol);
                        String fieldName = symbol.getSimpleName().toString();
                        fieldToColumn.put(fieldName, StringUtils.isEmpty(columnName) ? fieldName : columnName);
                        columnToField.put(StringUtils.isEmpty(columnName) ? fieldName : columnName, fieldName);
                    }
                });
        tableMapping.setColumnToField(columnToField);
        tableMapping.setFieldToColumn(fieldToColumn);
        nameModelMapping.put(tableMapping.getClassName(), tableMapping);
    }


    private String parseColumnAnnotation(Element element) {
        Column column = element.getAnnotation(Column.class);
        return column == null ? null : column.value();
    }

    private void handleDaoGenElement(Element element) {
        if (!(element.getKind() == ElementKind.INTERFACE)) {
            throw new TgDaoException("@DaoGen only annotated Interface");
        }
        DaoGen daoGen = element.getAnnotation(DaoGen.class);
        String modelClass = getAnnotatedClassForDaoGen(daoGen);
        List<SqlGen> sqlGens = ElementFilter.methodsIn(element.getEnclosedElements())
                .parallelStream()
                .map(executableElement -> handleExecutableElement(executableElement, modelClass))
                .filter(sqlGen -> sqlGen != null)
                .collect(Collectors.toList());
        TableMapping mapping = nameModelMapping.get(modelClass);
        if (mapping == null)
            throw new TgDaoException("can't get table info, check '" + modelClass + "' is annotated @Table");
        try {
            GenerateHelper.generate(daoGen.fileName(), ((Symbol.ClassSymbol) element).getQualifiedName().toString(), sqlGens, mapping);
        } catch (Exception e) {
            e.printStackTrace();
            throw new TgDaoException(e);
        }
    }

    private String getAnnotatedClassForDaoGen(DaoGen daoGen) {
        TypeMirror typeMirror = null;
        try {
            daoGen.model();
        } catch (MirroredTypeException mirroredTypeException) {
            //see https://area-51.blog/2009/02/13/getting-class-values-from-annotations-in-an-annotationprocessor/
            typeMirror = mirroredTypeException.getTypeMirror();
        }
        return typeMirror.toString();
    }

    public SqlGen handleExecutableElement(ExecutableElement executableElement, String modelClass) {
        Select select = executableElement.getAnnotation(Select.class);
        if (select != null) {
            return new SelectGen(executableElement, nameModelMapping.get(modelClass), select);
        }
        Count count = executableElement.getAnnotation(Count.class);
        if (count != null) {
            return new CountGen(executableElement, nameModelMapping.get(modelClass), count);
        }
        Insert insert = executableElement.getAnnotation(Insert.class);
        if (insert != null) {
            return new InsertGen(executableElement, nameModelMapping.get(modelClass), insert);
        }
        BatchInsert batchInsert = executableElement.getAnnotation(BatchInsert.class);
        if (batchInsert != null) {
            return new BatchInsertGen(executableElement, nameModelMapping.get(modelClass), batchInsert);
        }
        Update update = executableElement.getAnnotation(Update.class);
        if (update != null) {
            return new UpdateGen(executableElement, nameModelMapping.get(modelClass), update);
        }
        Delete delete = executableElement.getAnnotation(Delete.class);
        if (delete != null) {
            return new DeleteGen(executableElement, nameModelMapping.get(modelClass), delete);
        }
        return null;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

}
