package Table;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import Engine.SQLTerm;
import Engine.Utils;
import Exceptions.DBAppException;

/**
 * The Table class represents a table in a database.
 * It contains a vector of pages, each of which contains a vector of tuples
 * It also maintains a B+ tree index for each column in the table.
 */
public class Table extends FileHandler {
    // The name of the table
    public String name;

    // The column name of the clustering key
    private String clusteringKey;

    public Vector<Integer> pageNums;

    /**
     * Constructs a new Table with the given name, clustering key, and column types.
     * 
     * @throws IOException
     */
    public Table(String name, String clusteringKeyColumn) throws IOException {
        this.name = name;
        this.clusteringKey = clusteringKeyColumn;
        this.pageNums = new Vector<Integer>();
        this.saveTable();
    }

    /**
     * @return the name of the clustering key column.
     */
    public String getClusteringKey() {
        return clusteringKey;
    }

    /**
     * @param clusteringKey the name of the clustering key column.
     */
    public void setClusteringKey(String clusteringKey) {
        this.clusteringKey = clusteringKey;
    }

    public int getLastPageNum() {
        if (pageNums.size() == 0)
            return -1;

        return pageNums.get(pageNums.size() - 1);
    }

    /**
     * Adds a new page to the table with the given tuple as the first row.
     * The page is saved with a unique name based on the number of pages in the
     * table.
     *
     * @param newRow the tuple to be added as the first row of the new page
     * @throws IOException if an I/O error occurs while saving the page
     */
    public void addPage(Tuple newRow) throws IOException {
        int pageNum = getLastPageNum() + 1;
        pageNums.add(pageNum);

        Page page = new Page("page " + pageNum, newRow);
        page.savePage(this.name);
    }

    /**
     * Deserializes a specific page from the table.
     *
     * @param pageNum the page number to load
     * @return the deserialized page
     * @throws IOException            if an I/O error occurs while reading the page
     *                                file
     * @throws ClassNotFoundException if the class of a serialized object cannot be
     *                                found
     */
    public Page loadPage(int pageNum) throws IOException, ClassNotFoundException {
        String relativePagePath = "src\\main\\java\\Table\\" + this.name + "\\Pages\\";
        return (Page) super.loadInstance(relativePagePath, "page " + pageNum);
    }

    public BPlusTreeIndex loadIndex(String col) throws ClassNotFoundException, IOException {
        String relativeIndexPath = "src\\main\\java\\Table\\" + this.name + "\\Indicies\\";
        return (BPlusTreeIndex) super.loadInstance(relativeIndexPath, col);
    }

    /**
     * Saves/Serializes the table to a file.
     * 
     * @throws IOException if an I/O error occurs while saving the table.
     */
    public void saveTable() throws IOException {
        // Create the directory path with the table name
        String directoryPath = "src\\main\\java\\Table\\" + this.name + "\\";
        super.saveInstance(directoryPath, this.name);
    }

    /**
     * Inserts new row in correct position.
     * Handle different cases of inserting in new page, or in existing page.
     * Insert new row and its position for all available BPlusTree indicies.
     * 
     * TODO: Update page reference for shifted rows in BPlusTree indicies.
     * 
     * @param htblColNameType  a Hashtable representing the column names and their
     *                         corresponding data types.
     * @param htblColNameValue a Hashtable representing the column names and their
     *                         corresponding values for the new row.
     * @throws DBAppException         if an error occurs while performing the
     *                                database operation.
     * @throws ClassNotFoundException if the specified class cannot be found during
     *                                serialization/deserialization of a page.
     * @throws IOException            if an I/O error occurs during
     *                                serialization/deserialization of a page.
     */
    public void insertRow(Hashtable<String, String> htblColNameType, Hashtable<String, Object> htblColNameValue)
            throws DBAppException, ClassNotFoundException, IOException {
        Tuple newRow = Utils.convertInputToTuple(htblColNameType, htblColNameValue);
        int[] insertionPos = this.getInsertionPos(newRow, htblColNameType);

        insertIntoBPlusTrees(htblColNameValue, insertionPos[0], false);

        if (insertionPos[0] >= pageNums.size()) {
            // insert new row in new page
            this.addPage(newRow);
            return;
        }

        Page targetPage = loadPage(pageNums.get(insertionPos[0]));
        if (insertionPos[1] >= targetPage.getTuples().size()) {
            targetPage.addTuple(newRow);
            targetPage.savePage(this.name);
            return;
        }

        this.shiftRowsDownAndInsert(htblColNameType, insertionPos, newRow);
    }

    private void insertIntoBPlusTrees(Hashtable<String, Object> htblColNameValue,
            int pageNumIdx, boolean shifted) throws ClassNotFoundException, IOException {
        // insert into BPlusTree of each index if found
        for (String col : htblColNameValue.keySet()) {
            BPlusTreeIndex colIdx = loadIndex(col);
            if (colIdx == null) {
                continue;
            }

            Vector<String> pageRefs = colIdx.tree.search(htblColNameValue.get(col).toString());

            if (pageRefs == null) {
                pageRefs = new Vector<>();
            }

            if (shifted) {
                pageRefs.remove("page " + pageNums.get(pageNumIdx - 1));
            }
            if (pageNumIdx >= pageNums.size())
                pageRefs.add("page " + (getLastPageNum() + 1));
            else {
                pageRefs.add("page " + pageNums.get(pageNumIdx));
            }

            colIdx.tree.delete(htblColNameValue.get(col).toString());
            colIdx.tree.insert(htblColNameValue.get(col).toString(), pageRefs);
            colIdx.saveTree();
        }
    }

    /**
     * Shifts the rows down in the target page and inserts a new tuple at the
     * specified position.
     * 
     * @param targetPageNum The page number of the target page.
     * @param targetRowNum  The row number in the target page where the new tuple
     *                      should be inserted.
     * @param newRow        The new tuple to be inserted.
     * @throws DBAppException         If adding a tuple in a full page.
     * @throws IOException            If an I/O error occurs.
     * @throws ClassNotFoundException If the class of a serialized object cannot be
     *                                found.
     */
    private void shiftRowsDownAndInsert(Hashtable<String, String> htblColNameType, int[] targetPageIdxRowNum,
            Tuple newRow)
            throws DBAppException, IOException, ClassNotFoundException {
        Page targetPage = loadPage(pageNums.get(targetPageIdxRowNum[0]));
        Tuple curr, prev = targetPage.getTuples().get(targetPageIdxRowNum[1]);
        targetPage.getTuples().set(targetPageIdxRowNum[1], newRow);

        for (int row = targetPageIdxRowNum[1] + 1; row < targetPage.getTuples().size(); row++) {
            curr = targetPage.getTuples().get(row);
            targetPage.getTuples().set(row, prev);
            prev = curr;
        }

        if (targetPage.getTuples().size() < Page.maximumRowsCountInPage) {
            targetPage.addTuple(prev);
            targetPage.savePage(this.name);
            return;
        }

        Hashtable<String, Object> htblColNameValue = Utils.convertTupleToHashtable(htblColNameType, prev);
        insertIntoBPlusTrees(htblColNameValue, targetPageIdxRowNum[0] + 1, true);
        targetPage.savePage(this.name);

        for (int pageIdx = targetPageIdxRowNum[0] + 1; pageIdx < pageNums.size(); pageIdx++) {
            Page currPage = loadPage(pageNums.get(pageIdx));

            for (int row = 0; row < currPage.getTuples().size(); row++) {
                curr = currPage.getTuples().get(row);
                currPage.getTuples().set(row, prev);
                prev = curr;
            }

            if (currPage.getTuples().size() < Page.maximumRowsCountInPage) {
                currPage.addTuple(prev);
                currPage.savePage(this.name);
                return;
            }

            htblColNameValue = Utils.convertTupleToHashtable(htblColNameType, prev);
            insertIntoBPlusTrees(htblColNameValue, pageIdx + 1, true);
            currPage.savePage(this.name);
        }

        this.addPage(prev);
    }

    /**
     * Returns the insertion position of a new row in the table.
     * 
     * @param newRow          the new row to be inserted
     * @param htblColNameType a hashtable containing the column names and their
     *                        corresponding types
     * @return the insertion position in the format "pageNum_rowNum"
     * @throws DBAppException         if an error occurs in inserting a row
     * @throws IOException            if an I/O error occurs while reading or
     *                                writing data
     * @throws ClassNotFoundException if the specified class cannot be found during
     *                                deserialization
     */
    private int[] getInsertionPos(Tuple newRow, Hashtable<String, String> htblColNameType)
            throws DBAppException, IOException, ClassNotFoundException {

        int targetPageIdx = 0;
        int targetRowIdx = 0;
        int clusteringKeyIndex = Utils.getColIndex(htblColNameType, this.clusteringKey);

        String targetClusteringKey = newRow.getFields()[clusteringKeyIndex] + "";
        int pageStart = 0;
        int pageEnd = pageNums.size() - 1;
        int pageMid = 0;

        while (pageStart <= pageEnd) {
            pageMid = pageStart + (pageEnd - pageStart) / 2;

            Page currPage = loadPage(pageNums.get(pageMid));
            Vector<Tuple> currPageContent = currPage.getTuples();

            String firstRow = currPageContent.get(0).getFields()[clusteringKeyIndex] + "";
            String lastRow = currPageContent.get(currPageContent.size() - 1).getFields()[clusteringKeyIndex] + "";

            String type = htblColNameType.get(clusteringKey).toLowerCase();
            int comparison1 = Utils.compareKeys(targetClusteringKey, firstRow, type);
            int comparison2 = Utils.compareKeys(targetClusteringKey, lastRow, type);

            if (comparison1 < 0) {
                pageEnd = pageMid - 1;
                targetPageIdx = pageMid;
                targetRowIdx = 0;
            } else if (comparison2 >= 0) {
                pageStart = pageMid + 1;
                targetPageIdx = pageMid;
                targetRowIdx = currPageContent.size();
            } else {
                targetPageIdx = pageMid;
                targetRowIdx = currPage.findInsertionRow(newRow, clusteringKeyIndex, type);
                break;
            }
        }

        if (targetRowIdx == Page.maximumRowsCountInPage) {
            return new int[] { targetPageIdx + 1, 0 };
        }

        return new int[] { targetPageIdx, targetRowIdx };
    }

    /**
     * Updates a row in the table with the specified column name-value pairs, based
     * on the given clustering key value.
     *
     * @param htblColNameType       a Hashtable containing the column names and
     *                              their corresponding data types
     * @param htblColNameValue      a Hashtable containing the column names and
     *                              their new values
     * @param strClusteringKeyValue the value of the clustering key for the row to
     *                              be updated
     * @throws DBAppException         if an error occurs during the update operation
     * @throws ClassNotFoundException if the required class is not found during
     *                                deserializing
     * @throws IOException            if an I/O error occurs during deserializing a
     *                                page
     */
    public void updateRow(Hashtable<String, String> htblColNameType, Hashtable<String, Object> htblColNameValue,
            String strClusteringKeyValue)
            throws DBAppException, ClassNotFoundException, IOException {

        String clusteringKey = this.getClusteringKey();
        int clusteringKeyIndex = Utils.getColIndex(htblColNameType, this.clusteringKey);
        int pageStart = 0;
        int pageEnd = pageNums.size() - 1;
        int pageMid = 0;
        BPlusTreeIndex cluIdx = loadIndex(clusteringKey);
        while (pageStart <= pageEnd) {
            pageMid = pageStart + (pageEnd - pageStart) / 2;
            if (cluIdx != null) {
                Vector<String> pageRefs = cluIdx.tree.search(strClusteringKeyValue);
                pageMid = Integer.parseInt(pageRefs.get(0).split(" ")[1]);
            }
            Page currPage = loadPage(pageNums.get(pageMid));
            Vector<Tuple> currPageContent = currPage.getTuples();

            String firstRow = currPageContent.get(0).getFields()[clusteringKeyIndex] + "";
            String lastRow = currPageContent.get(currPageContent.size() - 1).getFields()[clusteringKeyIndex] + "";

            String type = htblColNameType.get(clusteringKey).toLowerCase();
            int comparison1 = Utils.compareKeys(strClusteringKeyValue, firstRow, type);
            int comparison2 = Utils.compareKeys(strClusteringKeyValue, lastRow, type);

            if (comparison1 < 0) {
                pageEnd = pageMid - 1;
            } else if (comparison2 > 0) {
                pageStart = pageMid + 1;
            } else {
                int begin = 0;
                int end = currPageContent.size();

                while (begin <= end) {
                    int mid = begin + (end - begin) / 2;
                    String currRow = currPageContent.get(mid).getFields()[clusteringKeyIndex] + "";
                    int comparison = Utils.compareKeys(strClusteringKeyValue, currRow, type);

                    if (comparison == 0) {
                        Tuple t = currPageContent.get(mid);

                        for (String col : htblColNameValue.keySet()) {
                            int colIndex = Utils.getColIndex(htblColNameType, col);
                            BPlusTreeIndex colIdx = loadIndex(col);
                            if (colIdx != null) {

                                Vector<String> pageRefs = colIdx.tree.search(htblColNameValue.get(col).toString());
                                if (pageRefs == null)
                                    pageRefs = new Vector<>();

                                pageRefs.add("page " + pageMid);
                                Vector<String> pageRefs2 = colIdx.tree.search(t.getFields()[colIndex].toString());
                                pageRefs2.remove("page " + pageMid);
                                // colIdx.tree.delete(t.getFields()[colIndex].toString());
                                // colIdx.tree.delete(htblColNameValue.get(col).toString());
                                colIdx.tree.insert(t.getFields()[colIndex].toString(), pageRefs2);
                                colIdx.tree.insert(htblColNameValue.get(col).toString(), pageRefs);
                                colIdx.saveTree();
                            }
                            t.getFields()[colIndex] = htblColNameValue.get(col);
                        }
                        currPage.savePage(this.name);
                        return;
                    } else if (comparison < 0) {
                        end = mid - 1;
                    } else {
                        begin = mid + 1;
                    }
                }
            }
        }
    }

    public void deleteRow(Hashtable<String, Object> htblColNameValue, Hashtable<String, String> htblColNameType)
            throws ClassNotFoundException, IOException, DBAppException {
        Vector<Integer> pagesToBeRemoved = new Vector<>();

        for (int pageNum : pageNums) {
            Page currPage = loadPage(pageNum);
            Page newPage = new Page(currPage.name);

            for (Tuple row : currPage.getTuples()) {
                for (String col : htblColNameValue.keySet()) {
                    int colIndex = 0;
                    for (String colName : htblColNameType.keySet()) {
                        if (colName.equals(col)) {
                            if (!row.getFields()[colIndex].equals(htblColNameValue.get(col))) {
                                newPage.addTuple(row);
                            }
                            break;
                        }
                        colIndex++;
                    }
                }
            }

            if (newPage.isEmpty()) {
                pagesToBeRemoved.add(pageNum);
            } else {
                newPage.savePage(this.name);
            }
        }

        for (int i = 0; i < pagesToBeRemoved.size(); i++) {
            pageNums.remove(pagesToBeRemoved.get(i));
        }

        saveTable();
    }

    public Iterator selectFromTable(SQLTerm[] arrSQLTerms, String[] strarrOperators,
            Hashtable<String, String> htblColNameType) throws DBAppException, ClassNotFoundException, IOException {
        String tableName = arrSQLTerms[0]._strTableName;
        Vector<Tuple> resultSet = new Vector<>();
        for (String string : strarrOperators) {
            if (!string.equals("AND") && !string.equals("OR") && !string.equals("XOR")) {
                throw new DBAppException("Invalid operator");
            }
        }
        for (SQLTerm term : arrSQLTerms) {
            if (!(term._strOperator.equals("=") || term._strOperator.equals("!=") || term._strOperator.equals(">")
                    || term._strOperator.equals("<") || term._strOperator.equals(">=")
                    || term._strOperator.equals("<="))) {
                throw new DBAppException("Invalid operator");
            }

            if (!term._strTableName.equals(tableName)) {
                throw new DBAppException("Invalid table name");
            }

        }
        for (int pageNum = 0; pageNum < pageNums.size(); pageNum++) {
            Page currPage = loadPage(pageNums.get(pageNum));

            for (int row = 0; row < currPage.getTuples().size(); row++) {
                Tuple currRow = currPage.getTuples().get(row);
                Vector<Boolean> tupleConditions = new Vector<>();

                for (SQLTerm term : arrSQLTerms) {
                    String colName = term._strColumnName;
                    Object colValue = term._objValue;
                    String operator = term._strOperator;
                    int colIndex = 0;

                    for (String col : htblColNameType.keySet()) {
                        if (col.equals(colName)) {
                            break;
                        }
                        colIndex++;
                    }
                    Object rowValue = currRow.getFields()[colIndex];
                    switch (operator) {
                        case "=":
                            tupleConditions.add(rowValue.equals(colValue));
                            break;

                        case "!=":
                            tupleConditions.add(!rowValue.equals(colValue));
                            break;

                        case ">":
                            tupleConditions.add(Utils.compareKeys(rowValue.toString(), colValue.toString(),
                                    htblColNameType.get(colName).toLowerCase()) > 0);
                            break;

                        case "<":
                            tupleConditions.add(Utils.compareKeys(rowValue.toString(), colValue.toString(),
                                    htblColNameType.get(colName).toLowerCase()) < 0);
                            break;

                        case ">=":
                            tupleConditions.add(Utils.compareKeys(rowValue.toString(), colValue.toString(),
                                    htblColNameType.get(colName).toLowerCase()) >= 0);
                            break;

                        case "<=":
                            tupleConditions.add(Utils.compareKeys(rowValue.toString(), colValue.toString(),
                                    htblColNameType.get(colName).toLowerCase()) <= 0);
                            break;

                        default:
                            throw new DBAppException("Invalid operator");
                    }

                }

                Vector<String> strVecOperators = new Vector<>();
                for (int m = 0; m < strarrOperators.length; m++) {
                    strVecOperators.add(strarrOperators[m]);
                }
                int m = 0;
                int countAnd = 0;
                int countOR = 0;
                int countXOR = 0;

                for (int d = 0; d < strVecOperators.size(); d++) {
                    if (strVecOperators.get(d).equals("AND")) {
                        countAnd = countAnd + 1;
                    }
                }
                while (countAnd > 0) {
                    if (strVecOperators.get(m).equals("AND")) {
                        tupleConditions.set(m, tupleConditions.get(m) && tupleConditions.get(m + 1));
                        tupleConditions.remove(m + 1);
                        strVecOperators.remove(m);
                        m = 0;
                        countAnd--;
                    } else {
                        m++;
                    }
                }

                for (int d = 0; d < strVecOperators.size(); d++) {
                    if (strVecOperators.get(d).equals("OR")) {
                        countOR = countOR + 1;
                    }
                }

                m = 0;
                while (countOR > 0) {
                    if (strVecOperators.get(m).equals("OR")) {
                        tupleConditions.set(m, tupleConditions.get(m) || tupleConditions.get(m + 1));
                        tupleConditions.remove(m + 1);
                        strVecOperators.remove(m);
                        m = 0;
                        countOR--;
                    } else {
                        m++;
                    }
                }

                for (int d = 0; d < strVecOperators.size(); d++) {
                    if (strVecOperators.get(d).equals("XOR")) {
                        countXOR = countXOR + d + 1;
                    }
                }

                m = 0;
                while (countXOR > 0) {
                    if (strVecOperators.get(m).equals("XOR")) {
                        tupleConditions.set(m, tupleConditions.get(m) ^ tupleConditions.get(m + 1));
                        tupleConditions.remove(m + 1);
                        strVecOperators.remove(m);
                        m = 0;
                        countXOR--;
                    } else {
                        m++;
                    }
                }
                if (tupleConditions.get(0)) {
                    resultSet.add(currRow);
                }
            }
        }

        return resultSet.iterator();
    }
}
