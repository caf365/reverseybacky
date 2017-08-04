package reversey.backy;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Class for parsing X86-64 programs.
 */
public class X86Parser {

    // Regular expressions used for parsing registers
    private static final String qRegNames = "r(ax|bx|cx|dx|si|di|bp|sp|8|9|1[0-5])";
    private static final String lRegNames = "e(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|1[0-5])d";
    private static final String wRegNames = "(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|1[0-5])w";
    private static final String bRegNames = "(al|ah|bl|bh|cl|ch|dl|dh|sil|dil|bpl|spl)|r(8|9|1[0-5])b";
    private static final String allRegNames = "(" + qRegNames + "|" + lRegNames + "|" + wRegNames + "|" + bRegNames + ")";

    // Regular expressions used for parsing operands
    private static final String decimalNumEx = "-?(?!0x)\\p{Digit}+";
    private static final String hexNumEx = "-?0x\\p{XDigit}+";
    private static final String constOpRegEx = "\\$?(?<const>" + decimalNumEx + "|" + hexNumEx + ")";

    private static final String regOpRegEx = "\\%(?<regName>\\p{Alnum}+)";
    private static final String memOpRegEx = "(?<imm>" + decimalNumEx + "|" + hexNumEx + ")?\\s*(?!\\(\\s*\\))\\(\\s*(%(?<base>\\p{Alnum}+))?\\s*(,\\s*%(?<index>\\p{Alnum}+)\\s*(,\\s*(?<scale>\\p{Digit}+))?)?\\s*\\)";
    private static final String labelOpRegEx = "(?<label>[\\.\\p{Alpha}][\\.\\w]*)";
    
    // ordering is important here: constant must go after mem
    private static final String operandRegEx = "\\s*(?<operand>" + memOpRegEx + "|" + regOpRegEx + "|" + labelOpRegEx + "|" + constOpRegEx + ")\\s*";

    /**
     * The line number that will be given to the next parsed line.
     */
    private int currLineNum;
    
    /**
     * Map for keeping track of all the labels we have parsed so far.
     */
    private final Map<String, x86Label> labels;

    private final Map<String, List<x86Instruction>> labelUsers;

    public X86Parser(){
        currLineNum = 0;
        labels = new HashMap<>();
        labelUsers = new HashMap<>();
    }
    
    /**
     * Class to represent information about the instruction being parsed,
     * including it's type, size, and operand requirements.
     */
    private class TypeAndOpRequirements {
        public final InstructionType type;
        public final OpSize instrSize;
        public final List<OperandRequirements> operandReqs;
        
        public TypeAndOpRequirements(InstructionType type, OpSize instrSize, List<OperandRequirements> opReqs) {
            this.type = type;
            this.instrSize = instrSize;
            this.operandReqs = opReqs;
        }
    }
    
    /**
     * Checks that instruction is a valid, supported x86 instruction.
     *
     * @param instrName The name of the instruction (e.g. addl)
     * @return Object containing the type and the operand requirements for this instruction.
     * @throws X86ParsingException If it is not a valid instruction or if the
     * size suffix is invalid.
     */
    private TypeAndOpRequirements parseTypeAndSize(String instrName) throws X86ParsingException {
        InstructionType type;
        OpSize size;
        List<OpSize> opSizes = new ArrayList<>();

        /* 
         * "sized" instructions are those that have an instruction name (e.g.
         * "add") followed by a single character suffix to indicate the size
         * (e.g. "q").
         */
        String validSizedInstrNames = "(?<name>add|sub|imul|idiv|xor|or|and|shl|sal|shr|sar|mov|lea|inc|dec|neg|not|push|pop|cmp|test|call|ret)(?<size>b|w|l|q)";
        Matcher sizedInstrMatcher = Pattern.compile(validSizedInstrNames).matcher(instrName);
        
        /*
         * "two sizes" instructions are those that have an instruction name followed
         * by two characters that indicate the size of two operands (e.g. "bl")
         */
        String validTwoSizedInstrNames = "(?<name>movz|movs)(b[wlq]|w[lq])";
        Matcher twoSizedInstrMatcher = Pattern.compile(validTwoSizedInstrNames).matcher(instrName);

        /*
         * "conditional" instructions are those whose operations are determined
         * by the status flags (e.g. the overflow flag).
         * Their suffix isn't a size, rather it is the condition to check for
         * (e.g. "ge" for "greater than or equal")
         * The "size" of these instructions is implicit (e.g. byte for SET).
         */
        String validConditionalInstrName = "((?<name>set|j)(?<op>e|ne|s|ns|g|ge|l|le|a|ae|b|be)|jmp)";
        Matcher condInstrMatcher = Pattern.compile(validConditionalInstrName).matcher(instrName);

        if (sizedInstrMatcher.matches()) {
            type = InstructionType.valueOf(sizedInstrMatcher.group("name").toUpperCase());

            // some instructions can only be quad sized so check for that first
            if (sizedInstrMatcher.group("name").matches("(lea|push|pop|call|ret)")
                    && !sizedInstrMatcher.group("size").equals("q")) {
                throw new X86ParsingException("instruction must have quad suffix (i.e. q)",
                        sizedInstrMatcher.start("size"),
                        instrName.length());
            }
            
            try {
                size = OpSize.getOpSizeFromAbbrev(sizedInstrMatcher.group("size"));
            } catch (X86ParsingException e) {
                throw new X86ParsingException("unexpected size suffix",
                            sizedInstrMatcher.start("size"),
                            instrName.length());
            }
            
            opSizes.add(size);
            
        } else if (twoSizedInstrMatcher.matches()) {
            type = InstructionType.valueOf(twoSizedInstrMatcher.group("name").toUpperCase());
            String suffix1 = twoSizedInstrMatcher.group(2).substring(0,1);
            String suffix2 = twoSizedInstrMatcher.group(2).substring(1);
            
            opSizes.add(OpSize.getOpSizeFromAbbrev(suffix1));
            size = OpSize.getOpSizeFromAbbrev(suffix2);
            opSizes.add(size);
            
        } else if (condInstrMatcher.matches()) {
            // The SET instruction is implicitly BYTE sized.
            // The JUMP instructions don't really have a size so BYTE is
            // arbitrarily chosen.
            type = InstructionType.valueOf(instrName.toUpperCase());
            size = OpSize.BYTE;
        } else {
            throw new X86ParsingException("invalid/unsupported instruction", 0, instrName.length());
        }
        
        List<OperandRequirements> opReqs = getOperandReqs(type, opSizes);
        return new TypeAndOpRequirements(type, size, opReqs);
    }

    /**
     * Get the size of the register with the given name.
     *
     * @param name The register's name
     * @return The size of the register.
     * @throws X86ParsingException The string did not contain a valid register
     * name.
     */
    public static OpSize getRegisterSize(String name) throws X86ParsingException {
        OpSize opSize = OpSize.BYTE;
        if (name.matches(lRegNames)) {
            opSize = OpSize.LONG;
        } else if (name.matches(qRegNames)) {
            opSize = OpSize.QUAD;
        } else if (name.matches(wRegNames)) {
            opSize = OpSize.WORD;
        } else if (name.matches(bRegNames)) {
            opSize = OpSize.BYTE;
        } else {
            throw new X86ParsingException("invalid register name", 0, name.length());
        }

        return opSize;
    }

    /**
     * Construct an operand based on a given string.
     *
     * @param str String containing the operand at the beginning.
     * @param opReqs The requirements for this operand.
     * @return The parsed operand.
     * @throws X86ParsingException There was an error parsing the string.
     */
    private Operand parseOperand(String str, OperandRequirements opReqs) throws X86ParsingException {
        Operand op = null;

        Matcher constMatcher = Pattern.compile(constOpRegEx).matcher(str);
        Matcher regMatcher = Pattern.compile(regOpRegEx).matcher(str);
        Matcher memMatcher = Pattern.compile(memOpRegEx).matcher(str);
        Matcher labelMatcher = Pattern.compile(labelOpRegEx).matcher(str);

        if (constMatcher.matches()) {
            // Found a constant operand
            if (!str.contains("$"))
                throw new X86ParsingException("Missing $ before constant", 
                                                constMatcher.start(),
                                                constMatcher.end());

            if (!opReqs.canBeConst())
                throw new X86ParsingException("operand cannot be a constant", 
                                                constMatcher.start(),
                                                constMatcher.end());
            
            // TODO: make sure constant fits in the size of the constant
            
            String constStr = constMatcher.group("const");
            int base = 10;
            if (constStr.contains("0x")) {
                base = 16;
                constStr = constStr.replace("0x", "");
            }
            op = new ConstantOperand(Integer.parseInt(constStr, base), 
                                        opReqs.getSize(),
                                        base);
        } else if (regMatcher.matches()) {
            // Found a register operand
            if (!opReqs.canBeReg())
                throw new X86ParsingException("operand cannot be a register", 
                                                regMatcher.start(), 
                                                regMatcher.end());
            
            String regName = regMatcher.group("regName");

            OpSize opSize = null;
            try {
                opSize = getRegisterSize(regName);
            } catch (X86ParsingException e) {
                throw new X86ParsingException(e.getMessage(),
                        regMatcher.start("regName") + e.getStartIndex(),
                        regMatcher.start("regName") + e.getEndIndex());
            }

            // Make sure the size of this register doesn't conflict with the
            // size the instruction uses/wants.
            // TODO: move this check to a "validation" method
            if (opSize != opReqs.getSize()) {
                throw new X86ParsingException("op size mismatch", 
                                                regMatcher.start("regName"), 
                                                regMatcher.end("regName"));
            }

            op = new RegOperand(regName, opSize);
        } else if (memMatcher.matches()) {
            // Found a memory operand
            if (!opReqs.canBeMem())
                throw new X86ParsingException("operand cannot be a memory location", 
                                                memMatcher.start(), 
                                                memMatcher.end());

            // All components (e.g. offset or base reg) are optional, although
            // at least one of them must be set.
            // Note that our regular expression should eliminate the possiblity
            // of getting a memory operand with no components present.
            // Look for an offset, which can be any integral value
            Integer offset = null;
            String offsetStr = memMatcher.group("imm");
            if (offsetStr != null) {
                int base = 10;
                if (offsetStr.contains("0x")) {
                    base = 16;
                    offsetStr = offsetStr.replace("0x", "");
                }
                offset = Integer.parseInt(offsetStr, base);
            }

            // Look for a base register, which should be a quad sized register
            String baseReg = memMatcher.group("base");
            if (baseReg != null) {
                OpSize baseOpSize = null;
                try {
                    baseOpSize = getRegisterSize(baseReg);
                } catch (X86ParsingException e) {
                    throw new X86ParsingException(e.getMessage(),
                            memMatcher.start("base") + e.getStartIndex(),
                            memMatcher.start("base") + e.getEndIndex());
                }
                if (baseOpSize != OpSize.QUAD) {
                    throw new X86ParsingException("base register must be quad sized",
                            memMatcher.start("base"),
                            memMatcher.end("base"));
                }
            }

            // Look for an index register, which should be a quad sized register
            String indexReg = memMatcher.group("index");
            if (indexReg != null) {
                OpSize indexOpSize = null;
                try {
                    indexOpSize = getRegisterSize(indexReg);
                } catch (X86ParsingException e) {
                    throw new X86ParsingException(e.getMessage(),
                            memMatcher.start("index") + e.getStartIndex(),
                            memMatcher.start("index") + e.getEndIndex());
                }

                if (indexOpSize != OpSize.QUAD) {
                    throw new X86ParsingException("index register must be quad sized",
                            memMatcher.start("index"),
                            memMatcher.end("index"));
                }
            }

            // Look for a scaling factor, which should be 1, 2, 4, or 8
            Integer scale = null;
            String scaleStr = memMatcher.group("scale");
            if (scaleStr != null) {
                scale = Integer.parseInt(scaleStr);
                if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
                    throw new X86ParsingException("invalid scaling factor",
                            memMatcher.start("scale"),
                            memMatcher.end("scale"));
                }
            }

            op = new MemoryOperand(baseReg, indexReg, scale, offset, opReqs.getSize());
        } else if (labelMatcher.matches()) {
            // Found a label operand
            String labelName = labelMatcher.group("label");
            if (labelName.matches(allRegNames))
                throw new X86ParsingException("Possibly missing % before register name", 
                                                labelMatcher.start(), 
                                                labelMatcher.end());
            
            // Found a label operand
             if (!opReqs.canBeLabel())
                throw new X86ParsingException("operand cannot be a label", 
                                                labelMatcher.start(), 
                                                labelMatcher.end());
            
            op = new LabelOperand(labelName, labels.get(labelName));
        } else {
            // TODO: throw X86ParsingException here
            System.out.println("ERROR: Unknown type of operand.");
            System.out.println("\t Tried to match " + str);
            System.exit(1);
        }
        return op;
    }

    /**
     * Parse all the operands in the given string. These operands should be
     * comma separated.
     *
     * @param operandsStr The string to parse for operands.
     * @param opReqs List of requirements for each operand that is expected.
     * @return The list of operands that were parsed.
     * @throws X86ParsingException There was a problem parsing the operands.
     */
    private List<Operand> parseOperands(String operandsStr, List<OperandRequirements> opReqs) throws X86ParsingException {
        List<Operand> operands = new ArrayList<>();

        Matcher m = Pattern.compile(operandRegEx).matcher(operandsStr);
        if (!m.find()) {
            return operands;
        }

        int nextIndex = -1;
        int opIndex = 0;
        try {
            // Parse the first operand
            String opStr = m.group("operand");
            Operand op = parseOperand(opStr, opReqs.get(opIndex));
            nextIndex = m.end();

            operands.add(op);
            opIndex++;

            // Update pattern to include the comma separator for the following
            // operands
            m = Pattern.compile("," + operandRegEx).matcher(operandsStr);

            // Keep parsing operands until we don't find any more
            while (m.find(nextIndex)) {
                if (opIndex >= opReqs.size()) {
                    throw new X86ParsingException("unexpected operand", m.start("operand"), m.end("operand"));
                }
                opStr = m.group("operand");
                op = parseOperand(opStr, opReqs.get(opIndex));
                nextIndex = m.end();
                operands.add(op);
                opIndex++;
            }
        } catch (X86ParsingException e) {
            throw new X86ParsingException(e.getMessage(),
                    m.start("operand") + e.getStartIndex(),
                    m.start("operand") + e.getEndIndex());
        }

        // Make sure there isn't any leftover cruft after the last parsed operand
        if (nextIndex != operandsStr.length()) {
            throw new X86ParsingException("unexpected value", nextIndex, operandsStr.length());
        }

        return operands;
    }

    /**
     * Create an x86-64 instruction by parsing a given string.
     *
     * @param instr A string representation of the instruction.
     * @return The parsed line.
     * @throws X86ParsingException There was a problem parsing the line.
     */
    public x86ProgramLine parseLine(String instr) throws X86ParsingException {
        Matcher commentMatcher = Pattern.compile("(?<other>.*)(?<comment>#.*)").matcher(instr);
        
        x86Comment c = null;
        if (commentMatcher.matches()){
            // This line contains a comment
            String comment = commentMatcher.group("comment");
            c = new x86Comment(comment);
            instr = commentMatcher.group("other");
        }
        
        if(instr.matches("\\s*")){
            return new x86BlankLine(currLineNum++, c);
        }
        
        Matcher instMatcher = Pattern.compile("\\s*(?<inst>\\p{Alpha}+)(\\s+(?<operands>.*))?").matcher(instr);
        Matcher labelMatcher = Pattern.compile("\\s*" + labelOpRegEx + ":\\s*").matcher(instr);
        
        // The line should be either a label or an instruction
        if (!instMatcher.matches() && !labelMatcher.matches()) {
            throw new X86ParsingException("nonsense input", 0, instr.length());
        }

        if (instMatcher.matches()) {
            // This line contains an X86 instruction.

            // Step 1: Get the name of the instruction and use that to determine
            // the type (i.e. what operation it is performing) and size of the
            // instruction.
            String instrName = instMatcher.group("inst");

            TypeAndOpRequirements instDetails = null;
            try {
                instDetails = parseTypeAndSize(instrName);
            } catch (X86ParsingException e) {
                throw new X86ParsingException(e.getMessage(),
                        instMatcher.start("inst") + e.getStartIndex(),
                        instMatcher.start("inst") + e.getEndIndex());
            }

            InstructionType instrType = instDetails.type;
            OpSize instrSize = instDetails.instrSize;
            List<OperandRequirements> opReqs = instDetails.operandReqs;

            // Step 2: Parse the operands (putting them into a list) then use
            // those operands plus the instruction type to create a new
            // X86Instruction.
            String operandsStr = instMatcher.group("operands");
            if (operandsStr != null) {

                List<Operand> operands = null;
                try {
                    operands = parseOperands(operandsStr, opReqs);
                } catch (X86ParsingException e) {
                    throw new X86ParsingException(e.getMessage(),
                            instMatcher.start("operands") + e.getStartIndex(),
                            instMatcher.start("operands") + e.getEndIndex());
                }

                if (operands.size() != instrType.numOperands()) {
                    throw new X86ParsingException("wrong number of operands",
                            instMatcher.start("operands"),
                            instr.length());
                } else if (instrType.numOperands() == 2) {
                    // This is a binary instruction (e.g. add), then second operand
                    // should be something we can write to (i.e. not a constant or a
                    // label)
                    if (operands.get(1) instanceof ConstantOperand) {
                        // FIXME: start/end index is not right here
                        throw new X86ParsingException("destination cannot be a constant",
                                instMatcher.start("operands"),
                                instr.length());
                    }
                    // TODO: check that the destination isn't a LabelOperand either
                    return new x86BinaryInstruction(instrType,
                            operands.get(0),
                            operands.get(1),
                            instrSize,
                            currLineNum++,
                            c);
                } else if (instrType.numOperands() == 1) {
                    // TODO: throw exception if destination is a constant (or a
                    // label for non-jump instructions)
                    x86UnaryInstruction inst = new x86UnaryInstruction(instrType,
                            operands.get(0),
                            instrSize,
                            currLineNum++,
                            c);

                    if (operands.get(0) instanceof LabelOperand) {
                        LabelOperand lo = (LabelOperand) operands.get(0);
                        String loName = lo.getName();
                        if (labelUsers.containsKey(loName)) {
                            labelUsers.get(loName).add(inst);
                        } else {
                            List<x86Instruction> l = new ArrayList<>();
                            l.add(inst);
                            labelUsers.put(loName, l);
                        }
                    }
                    return inst;
                }
                return null; // FIXME: throw exception
            } else {
                // nullary skullduggery
                return new x86NullaryInstruction(instrType,
                        instrSize,
                        currLineNum++,
                        c);
            }
        } else {
            // This line contains a label
            String labelName = labelMatcher.group("label");
            
            if (labelName.matches(allRegNames))
                throw new X86ParsingException("Label name should not be a register name", 
                                                labelMatcher.start("label"), 
                                                labelMatcher.end("label"));

            // Make sure this label doesn't already exist
            if (labels.containsKey(labelName)) {
                System.out.println("Duplicate label: " + labelName);
                throw new X86ParsingException("Duplicate label name",
                        labelMatcher.start("label"),
                        labelMatcher.end("label"));
            }

            x86Label l = new x86Label(labelName, currLineNum++, c);
            labels.put(labelName, l);
            if (labelUsers.containsKey(labelName)) {
                labelUsers.get(labelName).forEach((inst) -> {
                    inst.updateLabels(labelName, l);
                });
            }
            return l;
        } 
        // TODO: allow lines that contain both a label and an instruction?
    }

    /**
     * Resets the parser back to its starting state.
     */
    public void clear() {
        labels.clear();
        labelUsers.clear();
        currLineNum = 0;
    }
    
    /**
     * Sets the line number of next parsed line.
     * 
     * @param l The next line number.
     */
    public void setCurrLineNum(int l) {
        if (l >= 0) currLineNum = l;
    }
    
    /**
     * Removes the given label from our parser.
     * 
     * @param labelName The label to remove.
     */
    public void removeLabel(String labelName){
        labels.remove(labelName);
    }
    
    /**
     * Returns a list of operand requirements for an instruction of the given type
     * with the given operand sizes.
     * 
     * @param type The type of the instruction.
     * @param sizes A List of sizes for the operands the instruction expects.
     * 
     * @return A List containing the requirements for each of the operands of the instruction.
     */
    private static List<OperandRequirements> getOperandReqs(InstructionType type, List<OpSize> sizes) {
        List<OperandRequirements> opReqs = new ArrayList<>();
        
        // TODO: check that length of sizes is equal to number of operands 
        //  for instruction type
        
        switch (type) {
            case ADD:
            case SUB:
            case IMUL:
            case CMP:
            case OR:
            case AND:
            case TEST:
            case XOR:
            case MOV:
                opReqs.add(new OperandRequirements(sizes.get(0), true, true, true, false));
                opReqs.add(new OperandRequirements(sizes.get(0), false, true, true, false));
                break;
                
            case MOVZ:
            case MOVS:
                opReqs.add(new OperandRequirements(sizes.get(0), true, true, true, false));
                opReqs.add(new OperandRequirements(sizes.get(1), false, true, true, false));
                break;
                
            case SHL:
            case SAL:
            case SHR:
            case SRL:
            case SAR:
                opReqs.add(new OperandRequirements(OpSize.BYTE, true, false, false, false));
                opReqs.add(new OperandRequirements(sizes.get(0), false, true, true, false));
                break;
                
            case LEA:
                opReqs.add(new OperandRequirements(OpSize.QUAD, false, false, true, false));
                opReqs.add(new OperandRequirements(OpSize.QUAD, false, true, false, false));
                break;
                
            case IDIV:
                opReqs.add(new OperandRequirements(sizes.get(0), false, true, true, false));
                break;

                
            case INC:
            case DEC:
            case NEG:
            case NOT:
                opReqs.add(new OperandRequirements(sizes.get(0), false, true, true, false));
                break;
                
            case PUSH:
                opReqs.add(new OperandRequirements(OpSize.QUAD, true, true, true, false));
                break;
                
            case POP:
                opReqs.add(new OperandRequirements(OpSize.QUAD, false, true, true, false));
                break;
                
            case SETE:
            case SETNE:
            case SETS:
            case SETNS:
            case SETG:
            case SETGE:
            case SETL:
            case SETLE:
            case SETA:
            case SETAE:
            case SETB:
            case SETBE:
                opReqs.add(new OperandRequirements(OpSize.BYTE, false, true, true, false));
                break;
                
            case JE:
            case JNE:
            case JS:
            case JNS:
            case JG:
            case JGE:
            case JL:
            case JLE:
            case JA:
            case JAE:
            case JB:
            case JBE:
            case JMP:
            case CALL:
                opReqs.add(new OperandRequirements(OpSize.QUAD, false, false, false, true));
                break;
                
            case RET:
                // ret has no operands
                break;
            default:
        }
        
        return opReqs;
    }
}

/**
 * A class representing the requirements for an operand.
 * 
 * @author sat
 */
class OperandRequirements {
    private final OpSize size;
    private final boolean canBeConst;
    private final boolean canBeReg;
    private final boolean canBeMem;
    private final boolean canBeLabel;
    
    public OperandRequirements(OpSize size, boolean con, boolean reg, boolean mem, boolean lab) {
        this.size = size;
        this.canBeConst = con;
        this.canBeReg = reg;
        this.canBeMem = mem;
        this.canBeLabel = lab;
    }
    
    public OpSize getSize() { return this.size; }
    public boolean canBeConst() { return this.canBeConst; }
    public boolean canBeReg() { return this.canBeReg; }
    public boolean canBeMem() { return this.canBeMem; }
    public boolean canBeLabel() { return this.canBeLabel; }
}
