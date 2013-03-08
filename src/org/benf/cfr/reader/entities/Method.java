package org.benf.cfr.reader.entities;

import org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.VariableNamer;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.VariableNamerFactory;
import org.benf.cfr.reader.bytecode.analysis.types.*;
import org.benf.cfr.reader.entities.attributes.Attribute;
import org.benf.cfr.reader.entities.attributes.AttributeCode;
import org.benf.cfr.reader.entities.attributes.AttributeExceptions;
import org.benf.cfr.reader.entities.attributes.AttributeSignature;
import org.benf.cfr.reader.entityfactories.AttributeFactory;
import org.benf.cfr.reader.entityfactories.ContiguousEntityFactory;
import org.benf.cfr.reader.util.CollectionUtils;
import org.benf.cfr.reader.util.ConfusedCFRException;
import org.benf.cfr.reader.util.KnowsRawSize;
import org.benf.cfr.reader.util.SetFactory;
import org.benf.cfr.reader.util.bytestream.ByteData;
import org.benf.cfr.reader.util.functors.UnaryFunction;
import org.benf.cfr.reader.util.getopt.CFRState;
import org.benf.cfr.reader.util.output.Dumper;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: lee
 * Date: 17/04/2011
 * Time: 21:32
 * To change this template use File | Settings | File Templates.
 */

/* Too much in common with field - refactor.
 *
 */

public class Method implements KnowsRawSize {
    private static final long OFFSET_OF_ACCESS_FLAGS = 0;
    private static final long OFFSET_OF_NAME_INDEX = 2;
    private static final long OFFSET_OF_DESCRIPTOR_INDEX = 4;
    private static final long OFFSET_OF_ATTRIBUTES_COUNT = 6;
    private static final long OFFSET_OF_ATTRIBUTES = 8;

    private final long length;
    private final Set<AccessFlagMethod> accessFlags;
    private final Map<String, Attribute> attributes;
    private final short nameIndex;
    private final short descriptorIndex;
    private final AttributeCode codeAttribute;
    private final ConstantPool cp;
    private final VariableNamer variableNamer;
    private final MethodPrototype methodPrototype;
    private final ClassFile classFile;

    public Method(ByteData raw, ClassFile classFile, final ConstantPool cp) {
        this.cp = cp;
        this.classFile = classFile;
        this.nameIndex = raw.getS2At(OFFSET_OF_NAME_INDEX);
        this.accessFlags = AccessFlagMethod.build(raw.getS2At(OFFSET_OF_ACCESS_FLAGS));
        this.descriptorIndex = raw.getS2At(OFFSET_OF_DESCRIPTOR_INDEX);
        short numAttributes = raw.getS2At(OFFSET_OF_ATTRIBUTES_COUNT);
        ArrayList<Attribute> tmpAttributes = new ArrayList<Attribute>();
        tmpAttributes.ensureCapacity(numAttributes);
        long attributesLength = ContiguousEntityFactory.build(raw.getOffsetData(OFFSET_OF_ATTRIBUTES), numAttributes, tmpAttributes,
                new UnaryFunction<ByteData, Attribute>() {
                    @Override
                    public Attribute invoke(ByteData arg) {
                        return AttributeFactory.build(arg, cp);
                    }
                });
        this.attributes = ContiguousEntityFactory.addToMap(new HashMap<String, Attribute>(), tmpAttributes);
        this.length = OFFSET_OF_ATTRIBUTES + attributesLength;
        Attribute codeAttribute = attributes.get(AttributeCode.ATTRIBUTE_NAME);
        if (codeAttribute == null) {
            // Because we don't have a code attribute, we don't have a local variable table.
            this.variableNamer = VariableNamerFactory.getNamer(null, cp);
            this.codeAttribute = null;
        } else {
            this.codeAttribute = (AttributeCode) codeAttribute;
            // This rigamarole is neccessary because we don't provide the factory for the code attribute enough information
            // get get the Method (this).
            this.variableNamer = VariableNamerFactory.getNamer(this.codeAttribute.getLocalVariableTable(), cp);
            this.codeAttribute.setMethod(this);
        }
        this.methodPrototype = generateMethodPrototype();
    }

    private AttributeSignature getSignatureAttribute() {
        Attribute attribute = attributes.get(AttributeSignature.ATTRIBUTE_NAME);
        if (attribute == null) return null;
        return (AttributeSignature) attribute;
    }

    public VariableNamer getVariableNamer() {
        return variableNamer;
    }

    public ClassFile getClassFile() {
        return classFile;
    }

    @Override
    public long getRawByteLength() {
        return length;
    }

    public String getName() {
        return cp.getUTF8Entry(nameIndex).getValue();
    }

    /* This is a bit ugly - otherwise though we need to tie a variable namer to this earlier.
     * We can't always use the signature... in an enum, for example, it lies!
     *
     * Method  : <init> name : 30, descriptor 31
     * Descriptor ConstantUTF8[(Ljava/lang/String;I)V]
     * Signature Signature:ConstantUTF8[()V]
     *
     *
     */
    private MethodPrototype generateMethodPrototype() {
        AttributeSignature sig = getSignatureAttribute();
        ConstantPoolEntryUTF8 signature = sig == null ? null : sig.getSignature();
        ConstantPoolEntryUTF8 descriptor = cp.getUTF8Entry(descriptorIndex);
        ConstantPoolEntryUTF8 prototype = null;
        if (signature == null) {
            prototype = descriptor;
        } else {
            prototype = signature;
        }
        boolean isInstance = !accessFlags.contains(AccessFlagMethod.ACC_STATIC);
        boolean isVarargs = accessFlags.contains(AccessFlagMethod.ACC_VARARGS);
        MethodPrototype res = ConstantPoolUtils.parseJavaMethodPrototype(classFile, getName(), isInstance, prototype, cp, isVarargs, variableNamer);
        /*
         * Work around bug in inner class signatures.
         *
         * http://stackoverflow.com/questions/15131040/java-inner-class-inconsistency-between-descriptor-and-signature-attribute-clas
         */
        if (classFile.isInnerClass()) {
            if (signature != null) {
                MethodPrototype descriptorProto = ConstantPoolUtils.parseJavaMethodPrototype(classFile, getName(), isInstance, descriptor, cp, isVarargs, variableNamer);
                if (descriptorProto.getArgs().size() != res.getArgs().size()) {
                    // error due to inner class sig bug.
                    res = fixupInnerClassSignature(descriptorProto, res);
                }
            }
        }
        return res;
    }

    private static MethodPrototype fixupInnerClassSignature(MethodPrototype descriptor, MethodPrototype signature) {
        List<JavaTypeInstance> descriptorArgs = descriptor.getArgs();
        List<JavaTypeInstance> signatureArgs = signature.getArgs();
        if (signatureArgs.size() != descriptorArgs.size() - 1) {
            // It's not the known issue, can't really deal with it.
            return signature;
        }
        for (int x = 0; x < signatureArgs.size(); ++x) {
            if (!descriptorArgs.get(x + 1).equals(signatureArgs.get(x).getDeGenerifiedType())) {
                // Incompatible.
                return signature;
            }
        }
        // Ok.  We've fallen foul of the bad signature-on-inner-class
        // compiler bug.  Patch up the inner class signature so that it takes the implicit
        // outer this pointer.
        // Since we've got the ref to the mutable signatureArgs, let's be DISGUSTING and mutate that.
        signatureArgs.add(0, descriptorArgs.get(0));
        return signature;
    }

    public MethodPrototype getMethodPrototype() {
        return methodPrototype;
    }

    public String getSignatureText(boolean asClass) {
        Set<AccessFlagMethod> localAccessFlags = accessFlags;
        if (!asClass) {
            // Dumping as interface.
            localAccessFlags = SetFactory.newSet(localAccessFlags);
            localAccessFlags.remove(AccessFlagMethod.ACC_ABSTRACT);
        }
        String prefix = CollectionUtils.join(localAccessFlags, " ");
        String methodName = cp.getUTF8Entry(nameIndex).getValue();
        boolean constructor = methodName.equals("<init>");
        if (constructor) methodName = classFile.getClassType().toString();
        StringBuilder sb = new StringBuilder();
        if (!prefix.isEmpty()) sb.append(prefix).append(' ');
        sb.append(getMethodPrototype().getDeclarationSignature(methodName, constructor));
        Attribute exceptionsAttribute = attributes.get(AttributeExceptions.ATTRIBUTE_NAME);
        if (exceptionsAttribute != null) {
            sb.append(" throws ");
            boolean first = true;
            List<ConstantPoolEntryClass> exceptionClasses = ((AttributeExceptions) exceptionsAttribute).getExceptionClassList();
            for (ConstantPoolEntryClass exceptionClass : exceptionClasses) {
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }
                sb.append(exceptionClass.getTypeInstance(cp).getRawName());
            }
            if (asClass) sb.append(' '); // This is the kind of fiddly display we don't want to be doing here.. :(
        }
        return sb.toString();
    }

    public Op04StructuredStatement getAnalysis() {
        if (codeAttribute == null) throw new ConfusedCFRException("No code in this method to analyze");
        return codeAttribute.analyse();
    }

    public void analyse() {
        try {
            if (codeAttribute != null) codeAttribute.analyse();
        } catch (RuntimeException e) {
            System.out.println("While processing method : " + this.getName());
            throw e;
        }
    }

    public void dump(Dumper d, ConstantPool cp) {
        d.print(getSignatureText(true));
        if (codeAttribute == null) {
            d.print(";");
        } else {
            codeAttribute.dump(d, cp);
        }
    }


}
