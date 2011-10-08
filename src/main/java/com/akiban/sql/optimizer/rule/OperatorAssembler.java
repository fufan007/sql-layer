/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule;

import static com.akiban.sql.optimizer.rule.ExpressionAssembler.*;

import com.akiban.qp.operator.Operator;
import com.akiban.server.expression.std.Expressions;
import com.akiban.server.expression.std.LiteralExpression;

import com.akiban.server.aggregation.AggregatorRegistry;
import com.akiban.server.error.UnsupportedSQLException;

import com.akiban.server.expression.Expression;
import com.akiban.server.types.AkType;
import com.akiban.sql.optimizer.*;
import com.akiban.sql.optimizer.plan.*;
import com.akiban.sql.optimizer.plan.PhysicalSelect.PhysicalResultColumn;
import com.akiban.sql.optimizer.plan.ResultSet.ResultField;
import com.akiban.sql.optimizer.plan.Sort.OrderByExpression;
import com.akiban.sql.optimizer.plan.UpdateStatement.UpdateColumn;

import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.parser.ParameterNode;

import com.akiban.qp.operator.UndefBindings;
import com.akiban.qp.operator.API;
import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.operator.UpdateFunction;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.*;

import com.akiban.qp.expression.ExpressionRow;
import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.expression.RowBasedUnboundExpressions;
import com.akiban.qp.expression.UnboundExpressions;

import com.akiban.server.aggregation.Aggregator;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.GroupTable;

import com.akiban.server.api.dml.ColumnSelector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OperatorAssembler extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(OperatorAssembler.class);

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {
        new Assembler(plan).apply();
    }

    static class Assembler {
        private PlanContext planContext;
        private SchemaRulesContext rulesContext;
        private Schema schema;
        private final ExpressionAssembler expressionAssembler;

        public Assembler(PlanContext planContext) {
            this.planContext = planContext;
            rulesContext = (SchemaRulesContext)planContext.getRulesContext();
            schema = rulesContext.getSchema();
            expressionAssembler = new ExpressionAssembler(rulesContext);
        }

        public void apply() {
            planContext.setPlan(assembleStatement((BaseStatement)planContext.getPlan()));
        }
        
        protected BasePlannable assembleStatement(BaseStatement plan) {
            if (plan instanceof SelectQuery)
                return selectQuery((SelectQuery)plan);
            else if (plan instanceof InsertStatement)
                return insertStatement((InsertStatement)plan);
            else if (plan instanceof UpdateStatement)
                return updateStatement((UpdateStatement)plan);
            else if (plan instanceof DeleteStatement)
                return deleteStatement((DeleteStatement)plan);
            else
                throw new UnsupportedSQLException("Cannot assemble plan: " + plan, null);
        }

        protected PhysicalSelect selectQuery(SelectQuery selectQuery) {
            PlanNode planQuery = selectQuery.getQuery();
            RowStream stream = assembleQuery(planQuery);
            List<PhysicalResultColumn> resultColumns;
            if (planQuery instanceof ResultSet) {
                List<ResultField> results = ((ResultSet)planQuery).getFields();
                resultColumns = getResultColumns(results);
            }
            else {
                // VALUES results in column1, column2, ...
                resultColumns = getResultColumns(stream.rowType.nFields());
            }
            return new PhysicalSelect(stream.operator, stream.rowType,
                                      resultColumns, getParameterTypes());
        }

        protected PhysicalUpdate insertStatement(InsertStatement insertStatement) {
            PlanNode planQuery = insertStatement.getQuery();
            List<ExpressionNode> projectFields = null;
            if (planQuery instanceof Project) {
                Project project = (Project)planQuery;
                projectFields = project.getFields();
                planQuery = project.getInput();
            }
            RowStream stream = assembleQuery(planQuery);
            UserTableRowType targetRowType = 
                tableRowType(insertStatement.getTargetTable());
            List<Expression> inserts = null;
            if (projectFields != null) {
                // In the common case, we can project into a wider row
                // of the correct type directly.
                inserts = assembleExpressions(projectFields, stream.fieldOffsets);
            }
            else {
                // VALUES just needs each field, which will get rearranged below.
                int nfields = stream.rowType.nFields();
                inserts = new ArrayList<Expression>(nfields);
                for (int i = 0; i < nfields; i++) {
                    inserts.add(Expressions.field(stream.rowType, i));
                }
            }
            // Have a list of expressions in the order specified.
            // Want a list as wide as the target row with NULL
            // literals for the gaps.
            // TODO: That doesn't seem right. How are explicit NULLs
            // to be distinguished from the column's default value?
            Expression[] row = new Expression[targetRowType.nFields()];
            Arrays.fill(row, LiteralExpression.forNull());
            int ncols = inserts.size();
            for (int i = 0; i < ncols; i++) {
                Column column = insertStatement.getTargetColumns().get(i);
                row[column.getPosition()] = inserts.get(i);
            }
            inserts = Arrays.asList(row);
            stream.operator = API.project_Table(stream.operator, stream.rowType,
                                                targetRowType, inserts);
            UpdatePlannable plan = API.insert_Default(stream.operator);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        protected PhysicalUpdate updateStatement(UpdateStatement updateStatement) {
            RowStream stream = assembleQuery(updateStatement.getQuery());
            UserTableRowType targetRowType = 
                tableRowType(updateStatement.getTargetTable());
            assert (stream.rowType == targetRowType);
            List<UpdateColumn> updateColumns = updateStatement.getUpdateColumns();
            List<Expression> updates = assembleExpressionsA(updateColumns,
                                                            stream.fieldOffsets);
            // Have a list of expressions in the order specified.
            // Want a list as wide as the target row with Java nulls
            // for the gaps.
            // TODO: It might be simpler to have an update function
            // that knew about column offsets for ordered expressions.
            Expression[] row = new Expression[targetRowType.nFields()];
            for (int i = 0; i < updateColumns.size(); i++) {
                UpdateColumn column = updateColumns.get(i);
                row[column.getColumn().getPosition()] = updates.get(i);
            }
            updates = Arrays.asList(row);
            UpdateFunction updateFunction = 
                new ExpressionRowUpdateFunction(updates, targetRowType);
            UpdatePlannable plan = API.update_Default(stream.operator, updateFunction);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        protected PhysicalUpdate deleteStatement(DeleteStatement deleteStatement) {
            RowStream stream = assembleQuery(deleteStatement.getQuery());
            assert (stream.rowType == tableRowType(deleteStatement.getTargetTable()));
            UpdatePlannable plan = API.delete_Default(stream.operator);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        protected Operator assembleSubquery(Subquery subquery) {
            RowStream stream = assembleQuery(subquery.getQuery());
            return stream.operator;
        }

        // Assemble the top-level query. If there is a ResultSet at
        // the top, it is not handled here, since its meaning is
        // different for the different statement types.
        protected RowStream assembleQuery(PlanNode planQuery) {
            if (planQuery instanceof ResultSet)
                planQuery = ((ResultSet)planQuery).getInput();
            return assembleStream(planQuery);
        }

        // Assemble an ordinary stream node.
        protected RowStream assembleStream(PlanNode node) {
            if (node instanceof IndexScan)
                return assembleIndexScan((IndexScan)node);
            else if (node instanceof GroupScan)
                return assembleGroupScan((GroupScan)node);
            else if (node instanceof Select)
                return assembleSelect((Select)node);
            else if (node instanceof Flatten)
                return assembleFlatten((Flatten)node);
            else if (node instanceof AncestorLookup)
                return assembleAncestorLookup((AncestorLookup)node);
            else if (node instanceof BranchLookup)
                return assembleBranchLookup((BranchLookup)node);
            else if (node instanceof MapJoin)
                return assembleMapJoin((MapJoin)node);
            else if (node instanceof Product)
                return assembleProduct((Product)node);
            else if (node instanceof AggregateSource)
                return assembleAggregateSource((AggregateSource)node);
            else if (node instanceof Distinct)
                return assembleDistinct((Distinct)node);
            else if (node instanceof Sort            )
                return assembleSort((Sort)node);
            else if (node instanceof Limit)
                return assembleLimit((Limit)node);
            else if (node instanceof Project)
                return assembleProject((Project)node);
            else if (node instanceof ExpressionsSource)
                return assembleExpressionsSource((ExpressionsSource)node);
            else if (node instanceof NullSource)
                return assembleNullSource((NullSource)node);
            else
                throw new UnsupportedSQLException("Plan node " + node, null);
        }

        protected RowStream assembleIndexScan(IndexScan indexScan) {
            RowStream stream = new RowStream();
            IndexRowType indexRowType = schema.indexRowType(indexScan.getIndex());
            stream.operator = API.indexScan_Default(indexRowType, 
                                                    indexScan.isReverseScan(),
                                                    assembleIndexKeyRange(indexScan, null),
                                                    tableRowType(indexScan.getLeafMostTable()));
            stream.rowType = indexRowType;
            stream.fieldOffsets = new IndexFieldOffsets(indexScan, indexRowType);
            return stream;
        }

        protected RowStream assembleGroupScan(GroupScan groupScan) {
            RowStream stream = new RowStream();
            GroupTable groupTable = groupScan.getGroup().getGroup().getGroupTable();
            stream.operator = API.groupScan_Default(groupTable);
            stream.unknownTypesPresent = true;
            return stream;
        }

        protected RowStream assembleExpressionsSource(ExpressionsSource expressionsSource) {
            RowStream stream = new RowStream();
            stream.rowType = valuesRowType(expressionsSource.getFieldTypes());
            List<Row> rows = new ArrayList<Row>(expressionsSource.getExpressions().size());
            for (List<ExpressionNode> exprs : expressionsSource.getExpressions()) {
                List<Expression> expressions = new ArrayList<Expression>(exprs.size());
                for (ExpressionNode expr : exprs) {
                    expressions.add(assembleExpression(expr, stream.fieldOffsets));
                }
                rows.add(new ExpressionRow(stream.rowType, UndefBindings.only(),
                                           expressions));
            }
            stream.operator = API.valuesScan_Default(rows, stream.rowType);
            stream.fieldOffsets = new ColumnSourceFieldOffsets(expressionsSource, 
                                                               stream.rowType);
            return stream;
        }

        protected RowStream assembleNullSource(NullSource node) {
            return assembleExpressionsSource(new ExpressionsSource(Collections.<List<ExpressionNode>>emptyList()));
        }

        protected RowStream assembleSelect(Select select) {
            RowStream stream = assembleStream(select.getInput());
            for (ConditionExpression condition : select.getConditions()) {
                RowType rowType = stream.rowType;
                ColumnExpressionToIndex fieldOffsets = stream.fieldOffsets;
                if (rowType == null) {
                    // Pre-flattening case.
                    // TODO: Would it be better if earlier rule saved this?
                    TableSource table = 
                        SelectPreponer.getSingleTableConditionTable(condition);
                    rowType = tableRowType(table);
                    fieldOffsets = new ColumnSourceFieldOffsets(table, rowType);
                }
                stream.operator = API.select_HKeyOrdered(stream.operator,
                                                         rowType,
                                                         assembleExpression(condition, 
                                                                            fieldOffsets));
            }
            return stream;
        }

        protected RowStream assembleFlatten(Flatten flatten) {
            RowStream stream = assembleStream(flatten.getInput());
            List<TableNode> tableNodes = flatten.getTableNodes();
            TableNode tableNode = tableNodes.get(0);
            RowType tableRowType = tableRowType(tableNode);
            stream.rowType = tableRowType;
            int ntables = tableNodes.size();
            if (ntables == 1) {
                TableSource tableSource = flatten.getTableSources().get(0);
                if (tableSource != null)
                    stream.fieldOffsets = new ColumnSourceFieldOffsets(tableSource, 
                                                                       tableRowType);
            }
            else {
                Flattened flattened = new Flattened();
                flattened.addTable(tableRowType, flatten.getTableSources().get(0));
                for (int i = 1; i < ntables; i++) {
                    tableNode = tableNodes.get(i);
                    tableRowType = tableRowType(tableNode);
                    flattened.addTable(tableRowType, flatten.getTableSources().get(i));
                    API.JoinType flattenType = null;
                    switch (flatten.getJoinTypes().get(i-1)) {
                    case INNER:
                        flattenType = API.JoinType.INNER_JOIN;
                        break;
                    case LEFT:
                        flattenType = API.JoinType.LEFT_JOIN;
                        break;
                    case RIGHT:
                        flattenType = API.JoinType.RIGHT_JOIN;
                        break;
                    case FULL_OUTER:
                        flattenType = API.JoinType.FULL_JOIN;
                        break;
                    }
                    stream.operator = API.flatten_HKeyOrdered(stream.operator, 
                                                              stream.rowType,
                                                              tableRowType,
                                                              flattenType);
                    stream.rowType = stream.operator.rowType();
                }
                flattened.setRowType(stream.rowType);
                stream.fieldOffsets = flattened;
            }
            if (stream.unknownTypesPresent) {
                stream.operator = API.filter_Default(stream.operator,
                                                     Collections.singletonList(stream.rowType));
                stream.unknownTypesPresent = false;
            }
            return stream;
        }

        protected RowStream assembleAncestorLookup(AncestorLookup ancestorLookup) {
            RowStream stream = assembleStream(ancestorLookup.getInput());
            GroupTable groupTable = ancestorLookup.getDescendant().getGroup().getGroupTable();
            RowType inputRowType = stream.rowType; // The index row type.
            API.LookupOption flag = API.LookupOption.DISCARD_INPUT;
            if (!(inputRowType instanceof IndexRowType)) {
                // Getting from branch lookup.
                inputRowType = tableRowType(ancestorLookup.getDescendant());
                flag = API.LookupOption.KEEP_INPUT;
            }
            List<RowType> ancestorTypes = 
                new ArrayList<RowType>(ancestorLookup.getAncestors().size());
            for (TableNode table : ancestorLookup.getAncestors()) {
                ancestorTypes.add(tableRowType(table));
            }
            stream.operator = API.ancestorLookup_Default(stream.operator,
                                                         groupTable,
                                                         inputRowType,
                                                         ancestorTypes,
                                                         flag);
            stream.rowType = null;
            stream.fieldOffsets = null;
            return stream;
        }

        protected RowStream assembleBranchLookup(BranchLookup branchLookup) {
            RowStream stream;
            GroupTable groupTable = branchLookup.getSource().getGroup().getGroupTable();
            if (branchLookup.getInput() != null) {
                stream = assembleStream(branchLookup.getInput());
                RowType inputRowType = stream.rowType; // The index row type.
                API.LookupOption flag = API.LookupOption.DISCARD_INPUT;
                if (!(inputRowType instanceof IndexRowType)) {
                    // Getting from ancestor lookup.
                    inputRowType = tableRowType(branchLookup.getSource());
                    flag = API.LookupOption.KEEP_INPUT;
                }
                stream.operator = API.branchLookup_Default(stream.operator, 
                                                           groupTable, 
                                                           inputRowType,
                                                           tableRowType(branchLookup.getBranch()), 
                                                           flag);
            }
            else {
                stream = new RowStream();
                API.LookupOption flag = API.LookupOption.KEEP_INPUT;
                stream.operator = API.branchLookup_Nested(groupTable, 
                                                          tableRowType(branchLookup.getSource()),
                                                          tableRowType(branchLookup.getBranch()), 
                                                          flag,
                                                          currentBindingPosition());
                
            }
            stream.rowType = null;
            stream.unknownTypesPresent = true;
            stream.fieldOffsets = null;
            return stream;
        }

        protected RowStream assembleMapJoin(MapJoin mapJoin) {
            RowStream ostream = assembleStream(mapJoin.getOuter());
            pushBoundRow(ostream.fieldOffsets);
            RowStream stream = assembleStream(mapJoin.getInner());
            stream.operator = API.map_NestedLoops(ostream.operator, 
                                                  stream.operator,
                                                  currentBindingPosition());
            popBoundRow();
            return stream;
        }

        protected RowStream assembleProduct(Product product) {
            RowStream pstream = new RowStream();
            Flattened flattened = new Flattened();
            int nbound = 0;
            for (PlanNode subplan : product.getSubplans()) {
                if (pstream.operator != null) {
                    // The actual bound row is the branch row, which
                    // we don't access directly. Just give each
                    // product a separate position; nesting doesn't
                    // matter.
                    pushBoundRow(null);
                    nbound++;
                }
                RowStream stream = assembleStream(subplan);
                if (pstream.operator == null) {
                    pstream.operator = stream.operator;
                    pstream.rowType = stream.rowType;
                }
                else {
                    pstream.operator = API.product_NestedLoops(pstream.operator,
                                                               stream.operator,
                                                               pstream.rowType,
                                                               stream.rowType,
                                                               currentBindingPosition());
                    pstream.rowType = pstream.operator.rowType();
                }
                if (stream.fieldOffsets instanceof ColumnSourceFieldOffsets) {
                    TableSource table = ((ColumnSourceFieldOffsets)
                                         stream.fieldOffsets).getTable();
                    flattened.addTable(tableRowType(table), table);
                }
                else {
                    flattened.product((Flattened)stream.fieldOffsets);
                }
            }
            while (nbound > 0) {
                popBoundRow();
                nbound--;
            }
            flattened.setRowType(pstream.rowType);
            pstream.fieldOffsets = flattened;
            return pstream;
        }

        protected RowStream assembleAggregateSource(AggregateSource aggregateSource) {
            RowStream stream = assembleStream(aggregateSource.getInput());
            int nkeys = aggregateSource.getNGroupBy();
            // TODO: Temporary until aggregate_Partial fully functional.
            if (!aggregateSource.isProjectSplitOff()) {
                assert ((nkeys == 0) &&
                        (aggregateSource.getNAggregates() == 1));
                stream.operator = API.count_Default(stream.operator, stream.rowType);
                stream.rowType = stream.operator.rowType();
                stream.fieldOffsets = new ColumnSourceFieldOffsets(aggregateSource, 
                                                                   stream.rowType);
                return stream;
            }
            switch (aggregateSource.getImplementation()) {
            case PRESORTED:
            case UNGROUPED:
                break;
            default:
                {
                    // TODO: Could pre-aggregate now in PREAGGREGATE_RESORT case.
                    API.Ordering ordering = API.ordering();
                    for (int i = 0; i < nkeys; i++) {
                        ordering.append(Expressions.field(stream.rowType, i), true);
                    }
                    stream.operator = API.sort_Tree(stream.operator, stream.rowType, 
                                                    ordering);
                }
            }
            stream.operator = API.aggregate_Partial(stream.operator, nkeys,
                                                    rulesContext.getAggregatorRegistry(),
                                                    aggregateSource.getAggregateFunctions());
            stream.rowType = stream.operator.rowType();
            stream.fieldOffsets = new ColumnSourceFieldOffsets(aggregateSource,
                                                               stream.rowType);
            return stream;
        }

        protected RowStream assembleDistinct(Distinct distinct) {
            RowStream stream = assembleStream(distinct.getInput());
            stream.operator = API.aggregate_Partial(stream.operator,
                                                    stream.rowType.nFields(),
                                                    null,
                                                    Collections.<String>emptyList());
            // TODO: Probably want separate Distinct operator so that
            // row type does not change.
            stream.rowType = stream.operator.rowType();
            stream.fieldOffsets = null;
            return stream;
        }

        static final int INSERTION_SORT_MAX_LIMIT = 100;

        protected RowStream assembleSort(Sort sort) {
            RowStream stream = assembleStream(sort.getInput());
            API.Ordering ordering = API.ordering();
            for (OrderByExpression orderBy : sort.getOrderBy()) {
                ordering.append(assembleExpression(orderBy.getExpression(),
                                                   stream.fieldOffsets),
                                orderBy.isAscending());
            }
            int maxrows = -1;
            if (sort.getOutput() instanceof Limit) {
                Limit limit = (Limit)sort.getOutput();
                if (!limit.isOffsetParameter() && !limit.isLimitParameter()) {
                    maxrows = limit.getOffset() + limit.getLimit();
                }
            }
            else {
                // TODO: Also if input is VALUES, whose size we know in advance.
            }
            if ((maxrows >= 0) && (maxrows <= INSERTION_SORT_MAX_LIMIT))
                stream.operator = API.sort_InsertionLimited(stream.operator, stream.rowType,
                                                            ordering, maxrows);
            else
                stream.operator = API.sort_Tree(stream.operator, stream.rowType, ordering);
            return stream;
        }

        protected RowStream assembleLimit(Limit limit) {
            RowStream stream = assembleStream(limit.getInput());
            int nlimit = limit.getLimit();
            if ((nlimit < 0) && !limit.isLimitParameter())
                nlimit = Integer.MAX_VALUE; // Slight disagreement in saying unlimited.
            stream.operator = API.limit_Default(stream.operator, 
                                                limit.getOffset(), limit.isOffsetParameter(),
                                                nlimit, limit.isLimitParameter());
            return stream;
        }

        protected RowStream assembleProject(Project project) {
            RowStream stream = assembleStream(project.getInput());
            stream.operator = API.project_Default(stream.operator,
                                                  stream.rowType,
                                                  assembleExpressions(project.getFields(),
                                                                      stream.fieldOffsets));
            stream.rowType = stream.operator.rowType();
            // TODO: If Project were a ColumnSource, could use it to
            // calculate intermediate results and change downstream
            // references to use it instead of expressions. Then could
            // have a straight map of references into projected row.
            stream.fieldOffsets = null;
            return stream;
        }

        // Assemble a list of expressions from the given nodes.
        protected List<Expression> assembleExpressions(List<ExpressionNode> expressions,
                                                       ColumnExpressionToIndex fieldOffsets) {
            List<Expression> result = new ArrayList<Expression>(expressions.size());
            for (ExpressionNode expr : expressions) {
                result.add(assembleExpression(expr, fieldOffsets));
            }
            return result;
        }

        // Assemble a list of expressions from the given nodes.
        protected List<Expression> 
            assembleExpressionsA(List<? extends AnnotatedExpression> expressions,
                                 ColumnExpressionToIndex fieldOffsets) {
            List<Expression> result = new ArrayList<Expression>(expressions.size());
            for (AnnotatedExpression aexpr : expressions) {
                result.add(assembleExpression(aexpr.getExpression(), fieldOffsets));
            }
            return result;
        }

        // Assemble an expression against the given row offsets.
        protected Expression assembleExpression(ExpressionNode expr,
                                                ColumnExpressionToIndex fieldOffsets) {
            ColumnExpressionContext context = getColumnExpressionContext(fieldOffsets);
            if (expr instanceof SubqueryExpression) { 
                SubqueryExpression sexpr = (SubqueryExpression)expr;
                Operator subquery = assembleSubquery(sexpr.getSubquery());
                return expressionAssembler.assembleSubqueryExpression(sexpr, 
                                                                      context, 
                                                                      subquery);
            }
            return expressionAssembler.assembleExpression(expr, context);
        }

        // Get a list of result columns based on ResultSet expression names.
        protected List<PhysicalResultColumn> getResultColumns(List<ResultField> fields) {
            List<PhysicalResultColumn> columns = 
                new ArrayList<PhysicalResultColumn>(fields.size());
            for (ResultField field : fields) {
                columns.add(rulesContext.getResultColumn(field));
            }
            return columns;
        }

        // Get a list of result columns for unnamed columns.
        // This would correspond to top-level VALUES, which the parser
        // does not currently support.
        protected List<PhysicalResultColumn> getResultColumns(int ncols) {
            List<PhysicalResultColumn> columns = 
                new ArrayList<PhysicalResultColumn>(ncols);
            for (int i = 0; i < ncols; i++) {
                columns.add(rulesContext.getResultColumn(new ResultField("column" + (i+1))));
            }
            return columns;
        }

        // Generate key range bounds.
        protected IndexKeyRange assembleIndexKeyRange(IndexScan index,
                                                      ColumnExpressionToIndex fieldOffsets) {
            List<ExpressionNode> equalityComparands = index.getEqualityComparands();
            ExpressionNode lowComparand = index.getLowComparand();
            ExpressionNode highComparand = index.getHighComparand();
            if ((equalityComparands == null) &&
                (lowComparand == null) && (highComparand == null))
                return new IndexKeyRange(null, false, null, false);

            int nkeys = index.getIndex().getColumns().size();
            Expression[] keys = new Expression[nkeys];
            Arrays.fill(keys, LiteralExpression.forNull());

            int kidx = 0;
            if (equalityComparands != null) {
                for (ExpressionNode comp : equalityComparands) {
                    keys[kidx++] = assembleExpression(comp, fieldOffsets);
                }
            }

            if ((lowComparand == null) && (highComparand == null)) {
                IndexBound eq = getIndexBound(index.getIndex(), keys, kidx);
                return new IndexKeyRange(eq, true, eq, true);
            }
            else {
                Expression[] lowKeys = null, highKeys = null;
                boolean lowInc = false, highInc = false;
                int lidx = kidx, hidx = kidx;
                if (lowComparand != null) {
                    lowKeys = keys;
                    if (highComparand != null) {
                        highKeys = new Expression[nkeys];
                        System.arraycopy(keys, 0, highKeys, 0, kidx);
                    }
                }
                else if (highComparand != null) {
                    highKeys = keys;
                }
                if (lowComparand != null) {
                    lowKeys[lidx++] = assembleExpression(lowComparand, fieldOffsets);
                    lowInc = index.isLowInclusive();
                }
                if (highComparand != null) {
                    highKeys[hidx++] = assembleExpression(highComparand, fieldOffsets);
                    highInc = index.isHighInclusive();
                }
                IndexBound lo = getIndexBound(index.getIndex(), lowKeys, lidx);
                IndexBound hi = getIndexBound(index.getIndex(), highKeys, hidx);
                return new IndexKeyRange(lo, lowInc, hi, highInc);
            }
        }

        protected UserTableRowType tableRowType(TableSource table) {
            return tableRowType(table.getTable());
        }

        protected UserTableRowType tableRowType(TableNode table) {
            return schema.userTableRowType(table.getTable());
        }

        protected ValuesRowType valuesRowType(AkType[] fields) {
            return schema.newValuesType(fields);
        }
    
        /** Return an index bound for the given index and expressions.
         * @param index the index in use
         * @param keys {@link Expression}s for index lookup key
         * @param nkeys number of keys actually in use
         */
        protected IndexBound getIndexBound(Index index, Expression[] keys, int nkeys) {
            if (keys == null) 
                return null;
            return new IndexBound(getIndexExpressionRow(index, keys),
                                  getIndexColumnSelector(index, nkeys));
        }

        /** Return a column selector that enables the first <code>nkeys</code> fields
         * of a row of the index's user table. */
        protected ColumnSelector getIndexColumnSelector(final Index index, 
                                                        final int nkeys) {
            assert nkeys <= index.getColumns().size() : index + " " + nkeys;
                return new ColumnSelector() {
                        public boolean includesColumn(int columnPosition) {
                            return columnPosition < nkeys;
                        }
                    };
        }

        /** Return a {@link Row} for the given index containing the given
         * {@link Expression} values.  
         */
        protected UnboundExpressions getIndexExpressionRow(Index index, 
                                                           Expression[] keys) {
            RowType rowType = schema.indexRowType(index);
            return new RowBasedUnboundExpressions(rowType, Arrays.asList(keys));
        }

        // Get the required type for any parameters to the statement.
        protected DataTypeDescriptor[] getParameterTypes() {
            AST ast = ASTStatementLoader.getAST(planContext);
            if (ast == null)
                return null;
            List<ParameterNode> params = ast.getParameters();
            if ((params == null) || params.isEmpty())
                return null;
            int nparams = 0;
            for (ParameterNode param : params) {
                if (nparams < param.getParameterNumber() + 1)
                    nparams = param.getParameterNumber() + 1;
            }
            DataTypeDescriptor[] result = new DataTypeDescriptor[nparams];
            for (ParameterNode param : params) {
                result[param.getParameterNumber()] = param.getType();
            }        
            return result;
        }
        
        /* Bindings-related state */

        protected int bindingsOffset = -1;
        protected Stack<ColumnExpressionToIndex> boundRows = null;

        protected void ensureBoundRows() {
            if (boundRows == null) {
                boundRows = new Stack<ColumnExpressionToIndex>();
                
                // Binding positions are shared with parameter positions.
                AST ast = ASTStatementLoader.getAST(planContext);
                if (ast == null)
                    bindingsOffset = 0;
                else {
                    List<ParameterNode> params = ast.getParameters();
                    if (params == null)
                        bindingsOffset = 0;
                    else
                        bindingsOffset = ast.getParameters().size();
                }
            }
        }

        protected void pushBoundRow(ColumnExpressionToIndex boundRow) {
            ensureBoundRows();
            boundRows.push(boundRow);
        }

        protected void popBoundRow() {
            boundRows.pop();
        }

        protected int currentBindingPosition() {
            ensureBoundRows();
            return bindingsOffset + boundRows.size() - 1;
        }

        class ColumnBoundRows implements ColumnExpressionContext {
            ColumnExpressionToIndex current;

            @Override
            public ColumnExpressionToIndex getCurrentRow() {
                return current;
            }

            @Override
            public List<ColumnExpressionToIndex> getBoundRows() {
                ensureBoundRows();
                return boundRows;
            }

            @Override
            public int getBindingsOffset() {
                return bindingsOffset;
            }
        }
        
        ColumnBoundRows columnBoundRows = new ColumnBoundRows();

        protected ColumnExpressionContext getColumnExpressionContext(ColumnExpressionToIndex current) {
            columnBoundRows.current = current;
            return columnBoundRows;
        }

    }

    // Struct for multiple value return from assembly.
    static class RowStream {
        Operator operator;
        RowType rowType;
        boolean unknownTypesPresent;
        ColumnExpressionToIndex fieldOffsets;
    }

    static abstract class BaseColumnExpressionToIndex implements ColumnExpressionToIndex {
        protected RowType rowType;

        BaseColumnExpressionToIndex(RowType rowType) {
            this.rowType = rowType;
        }

        @Override
        public RowType getRowType() {
            return rowType;
        }
    }

    // Single table-like source.
    static class ColumnSourceFieldOffsets extends BaseColumnExpressionToIndex {
        private ColumnSource source;

        public ColumnSourceFieldOffsets(ColumnSource source, RowType rowType) {
            super(rowType);
            this.source = source;
        }

        public ColumnSource getSource() {
            return source;
        }

        public TableSource getTable() {
            return (TableSource)source;
        }

        @Override
        public int getIndex(ColumnExpression column) {
            if (column.getTable() != source) 
                return -1;
            else
                return column.getPosition();
        }
    }

    // Index used as field source (e.g., covering).
    static class IndexFieldOffsets extends BaseColumnExpressionToIndex {
        private IndexScan index;

        public IndexFieldOffsets(IndexScan index, RowType rowType) {
            super(rowType);
            this.index = index;
        }

        @Override
        // Access field of the index row itself. 
        // (Covering index or condition before lookup.)
        public int getIndex(ColumnExpression column) {
            return index.getColumns().indexOf(column);
        }
    }

    // Flattened row.
    static class Flattened extends BaseColumnExpressionToIndex {
        Map<TableSource,Integer> tableOffsets = new HashMap<TableSource,Integer>();
        int nfields;
            
        Flattened() {
            super(null);        // Worked out later.
        }

        public void setRowType(RowType rowType) {
            this.rowType = rowType;
        }

        @Override
        public int getIndex(ColumnExpression column) {
            Integer tableOffset = tableOffsets.get(column.getTable());
            if (tableOffset == null)
                return -1;
            return tableOffset + column.getPosition();
        }

        public void addTable(RowType rowType, TableSource table) {
            if (table != null)
                tableOffsets.put(table, nfields);
            nfields += rowType.nFields();
        }

        // Tack on another flattened using product rules.
        public void product(final Flattened other) {
            List<TableSource> otherTables = 
                new ArrayList<TableSource>(other.tableOffsets.keySet());
            Collections.sort(otherTables,
                             new Comparator<TableSource>() {
                                 @Override
                                 public int compare(TableSource x, TableSource y) {
                                     return other.tableOffsets.get(x) - other.tableOffsets.get(y);
                                 }
                             });
            for (int i = 0; i < otherTables.size(); i++) {
                TableSource otherTable = otherTables.get(i);
                if (!tableOffsets.containsKey(otherTable)) {
                    tableOffsets.put(otherTable, nfields);
                    // Width in other.tableOffsets.
                    nfields += (((i+1 >= otherTables.size()) ?
                                 other.nfields :
                                 other.tableOffsets.get(otherTables.get(i+1))) -
                                other.tableOffsets.get(otherTable));
                }
            }
        }
    }

}