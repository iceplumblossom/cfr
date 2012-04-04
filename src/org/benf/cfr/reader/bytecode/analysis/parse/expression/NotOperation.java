package org.benf.cfr.reader.bytecode.analysis.parse.expression;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.LValueCollector;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 22/03/2012
 * Time: 06:45
 * To change this template use File | Settings | File Templates.
 */
public class NotOperation implements ConditionalExpression {
    private ConditionalExpression lhs;

    public NotOperation(ConditionalExpression lhs) {
        this.lhs = lhs;
    }

    @Override
    public Expression replaceSingleUsageLValues(LValueCollector lValueCollector) {
        return this;
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public String toString() {
        return "!(" + lhs.toString() +")";
    }
}