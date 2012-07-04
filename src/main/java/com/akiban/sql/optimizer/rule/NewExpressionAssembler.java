/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule;

import com.akiban.server.collation.AkCollator;
import com.akiban.server.expression.std.Comparison;
import com.akiban.server.types3.texpressions.TPreparedExpression;
import com.akiban.sql.optimizer.plan.CastExpression;
import com.akiban.sql.optimizer.plan.ColumnExpression;
import com.akiban.sql.optimizer.plan.ComparisonCondition;
import com.akiban.sql.optimizer.plan.ConstantExpression;
import com.akiban.sql.optimizer.plan.ExpressionNode;
import com.akiban.sql.optimizer.plan.ParameterExpression;

import java.util.List;

public final class NewExpressionAssembler extends ExpressionAssembler<TPreparedExpression> {
    @Override
    protected TPreparedExpression assembleFunction(ExpressionNode functionNode, String functionName,
                                                   List<ExpressionNode> argumentNodes,
                                                   ColumnExpressionContext columnContext,
                                                   SubqueryOperatorAssembler<TPreparedExpression> subqueryAssembler) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression assembleColumnExpression(ColumnExpression column,
                                                           ColumnExpressionContext columnContext) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression assembleCastExpression(CastExpression castExpression,
                                                         ColumnExpressionContext columnContext,
                                                         SubqueryOperatorAssembler<TPreparedExpression> subqueryAssembler) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected List<TPreparedExpression> assembleExpressions(List<ExpressionNode> expressions,
                                                            ColumnExpressionContext columnContext,
                                                            SubqueryOperatorAssembler<TPreparedExpression> subqueryAssembler) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression literal(ConstantExpression expression) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression variable(ParameterExpression expression) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression compare(TPreparedExpression left, Comparison comparison, TPreparedExpression right) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression collate(TPreparedExpression left, Comparison comparison, TPreparedExpression right, AkCollator collator) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected AkCollator collator(ComparisonCondition cond, TPreparedExpression left, TPreparedExpression right) {
        throw new UnsupportedOperationException(); // TODO
    }

    @Override
    protected TPreparedExpression in(TPreparedExpression lhs, List<TPreparedExpression> rhs) {
        throw new UnsupportedOperationException(); // TODO
    }
}
