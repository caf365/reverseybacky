package reversey.backy;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javafx.util.Pair;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;

/**
 * An abstract class representing an x86-64 instruction.
 *
 * @author Dr. Sat
 */
public abstract class x86Instruction {
	private static final String qRegNames = "r(ax|bx|cx|dx|si|di|bp|sp|8|9|1[0-5])";
	private static final String lRegNames = "e(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|1[0-5])d";
	private static final String wRegNames = "(ax|bx|cx|dx|si|di|bp|sp)|r(8|9|1[0-5])w";
	private static final String bRegNames = "(al|ah|bl|bh|cl|ch|dl|dh|sil|dil|bpl|spl)|r(8|9|1[0-5])b";
	private static final String allRegNames = "(" + qRegNames + "|" + lRegNames + "|" + wRegNames + "|" + bRegNames + ")";

	private static final String constOpRegEx = "\\$(?<const>-?\\p{Digit}+)";
	private static final String regOpRegEx = "\\%(?<regName>\\p{Alnum}+)";
	private static final String memOpRegEx = "(?<imm>-?\\p{Digit}+)?\\s*(?!\\(\\s*\\))\\(\\s*(%(?<base>\\p{Alnum}+))?\\s*(,\\s*%(?<index>\\p{Alnum}+)\\s*(,\\s*(?<scale>\\p{Digit}+))?)?\\s*\\)";
	private static final String operandRegEx = "\\s*(?<operand>" + constOpRegEx + "|" + regOpRegEx + "|" + memOpRegEx + ")\\s*";

	/**
	 * The operand where the instruction will write its results.
	 */
	protected Operand destination;

	/**
	 * The type of instruction (e.g. add)
	 */
	protected InstructionType type;

	/**
	 * The number of bytes the operation works on.
	 */
	protected OpSize opSize;

	// Getters
	public InstructionType getType() { return this.type; }
	public OpSize getOpSize() { return this.opSize; }

	/**
	 * Perform the operation specific to the instruction.
	 * 
	 * @param state The state of the machine before evaluation begins.
	 * @return State of machine after evaluating the instruction.
	 */
	public abstract MachineState eval(MachineState state);

	/**
	 * Checks that instruction is a valid, supported x86 instruction.
	 *
	 * @param instrName The name of the instruction (e.g. addl)
	 * @return A pair containing both the instruction type and op size
	 * unary instruction.
	 */
	public static Pair<InstructionType, OpSize> parseTypeAndSize(String instrName) throws X86ParsingException {
		InstructionType type;
		OpSize size;

		String validTypedInstrNames = "(?<name>add|sub|xor|or|and|shl|sal|shr|sar|mov|lea|inc|dec|neg|not|push|pop|cmp|test)(?<size>b|w|l|q)";
		String validSetInstrNames = "(?<name>set)(?<op>e|ne|s|ns|g|ge|l|le)";

		Matcher typedMatcher = Pattern.compile(validTypedInstrNames).matcher(instrName);
		Matcher setMatcher = Pattern.compile(validSetInstrNames).matcher(instrName);

		if (typedMatcher.matches()) {
			type = InstructionType.valueOf(typedMatcher.group("name").toUpperCase());
			switch (typedMatcher.group("size")) {
				case "b":
					size = OpSize.BYTE;
					break;
				case "w":
					size = OpSize.WORD;
					break;
				case "l":
					size = OpSize.LONG;
					break;
				case "q":
					size = OpSize.QUAD;
					break;
				default:
					throw new X86ParsingException("unexpected size suffix", typedMatcher.start("size"), instrName.length());
			}
		}
		else if (setMatcher.matches()) {
			// The SET instruction is implicitly BYTE sized.
			type = InstructionType.valueOf(instrName.toUpperCase());
			size = OpSize.BYTE;
		}
		else {
			throw new X86ParsingException("invalid/unsupported instruction", 0, instrName.length());
		}

		return new Pair<InstructionType, OpSize>(type, size);
	}

	/**
	 * Get the size of the register with the given name.
	 *
	 * @param name The register's name
	 * @return The size of the register.
	 */
	public static OpSize getRegisterSize(String name) throws X86ParsingException {
		OpSize opSize = OpSize.BYTE;
		if (name.matches(lRegNames)) {
			opSize = OpSize.LONG;
		}
		else if (name.matches(qRegNames)) {
			opSize = OpSize.QUAD;
		}
		else if (name.matches(wRegNames)) {
			opSize = OpSize.WORD;
		}
		else if (name.matches(bRegNames)) {
			opSize = OpSize.BYTE;
		}
		else {
			throw new X86ParsingException("invalid register name", 0, name.length());
		}

		return opSize;
	}

	/**
	 * Construct an operand based on a given string.
	 *
	 * @param String that contains the operand at the beginning.
	 * @return The parsed operand.
	 */
	public static Operand parseOperand(String str, OpSize instrOpSize) throws X86ParsingException {
		Operand op = null;

		Matcher constMatcher = Pattern.compile(constOpRegEx).matcher(str);
		Matcher regMatcher = Pattern.compile(regOpRegEx).matcher(str);
		Matcher memMatcher = Pattern.compile(memOpRegEx).matcher(str);

		if (constMatcher.matches()) {
			// constant operand
			op = new ConstantOperand(Integer.parseInt(constMatcher.group("const"))); // TODO: handle hex
		}
		else if (regMatcher.matches()) {
			// register operand
			String[] splits = str.split(",");

			String regName = regMatcher.group("regName");

			OpSize opSize = null;
			try {
				opSize = getRegisterSize(regName);
			} catch (X86ParsingException e) {
				throw new X86ParsingException(e.getMessage(),
						regMatcher.start("regName")+e.getStartIndex(),
						regMatcher.start("regName")+e.getEndIndex());
			}


			// TODO: move this to a "validation" method
			if (opSize != instrOpSize) {
				throw new X86ParsingException("op size mismatch", regMatcher.start("regName"), regMatcher.end("regName"));
			}

			op = new RegOperand(regName, opSize);
		}
		else if (memMatcher.matches()) {
			// memory operand

			// All components (e.g. offset or base reg) are optional, although
			// at least one of them must be set.
			int offset = 0;
			String offsetStr = memMatcher.group("imm");
			if (offsetStr != null) {
				offset = Integer.parseInt(offsetStr); // TODO: handle hex
			}

			String baseReg = memMatcher.group("base");
			if (baseReg != null) {
				OpSize baseOpSize = null;
				try {
					baseOpSize = getRegisterSize(baseReg);
				} catch (X86ParsingException e) {
					throw new X86ParsingException(e.getMessage(),
							memMatcher.start("base")+e.getStartIndex(),
							memMatcher.start("base")+e.getEndIndex());
				}
				if (baseOpSize != OpSize.QUAD) {
					throw new X86ParsingException("base register must be quad sized",
						   							memMatcher.start("base"),
													memMatcher.end("base"));
				}
			}

			String indexReg = memMatcher.group("index");
			if (indexReg != null) {
				OpSize indexOpSize = null;
				try {
					indexOpSize = getRegisterSize(indexReg);
				} catch (X86ParsingException e) {
					throw new X86ParsingException(e.getMessage(),
							memMatcher.start("index")+e.getStartIndex(),
							memMatcher.start("index")+e.getEndIndex());
				}

				if (indexOpSize != OpSize.QUAD) {
					throw new X86ParsingException("index register must be quad sized", 
													memMatcher.start("index"), 
													memMatcher.end("index"));
				}
			}

			int scale = 1;
			String scaleStr = memMatcher.group("scale");
			if (scaleStr != null) {
				scale = Integer.parseInt(scaleStr);
				if (scale != 1 && scale != 2 && scale != 4 && scale != 8) {
					throw new X86ParsingException("invalid scaling factor", 
													memMatcher.start("scale"),
													memMatcher.end("scale"));
				}
			}

			op = new MemoryOperand(baseReg, indexReg, scale, offset, instrOpSize);
		}

		return op;
	}

	public static List<Operand> parseOperands(String operandsStr, OpSize opSize) throws X86ParsingException {
		List<Operand> operands = new ArrayList<Operand>();

		Matcher m = Pattern.compile(operandRegEx).matcher(operandsStr);
		if (!m.find()) {
			return operands;
		}

		int nextIndex = -1;
		try {
			String opStr = m.group("operand");
			Operand op = parseOperand(opStr, opSize);
			nextIndex = m.end();

			operands.add(op);

			m = Pattern.compile("," + operandRegEx).matcher(operandsStr);

			while (m.find(nextIndex)) {
				opStr = m.group("operand");
				op = parseOperand(opStr, opSize);
				nextIndex = m.end();
				operands.add(op);
			}
		} catch (X86ParsingException e) {
			throw new X86ParsingException(e.getMessage(),
					m.start("operand")+e.getStartIndex(),
					m.start("operand")+e.getEndIndex());
		}

		if (nextIndex != operandsStr.length()) {
			throw new X86ParsingException("unexpected value", nextIndex, operandsStr.length());
		}

		return operands;
	}

	/**
	 * Create an x86-64 instruction by parsing a given string.
	 *
	 * @param instr A string representation of the instruction.
	 * @return The parsed instruction.
	 */
	public static x86Instruction parseInstruction(String instr) throws X86ParsingException {
		Matcher instMatcher = Pattern.compile("\\s*(?<inst>\\S+)\\s+(?<operands>.*)").matcher(instr);

		if (!instMatcher.matches()) {
			throw new X86ParsingException("nonsense input", 0, instr.length());
		}

		String instrName = instMatcher.group("inst");

		Pair<InstructionType, OpSize> instDetails = null;
		try {
			instDetails = parseTypeAndSize(instrName);
		} catch (X86ParsingException e) {
			throw new X86ParsingException(e.getMessage(),
					instMatcher.start("inst")+e.getStartIndex(),
					instMatcher.start("inst")+e.getEndIndex());
		}

		InstructionType instrType = instDetails.getKey();
		OpSize opSize = instDetails.getValue();

		String operandsStr = instMatcher.group("operands");

		List<Operand> operands = null;
		try {
			operands = parseOperands(operandsStr, opSize);
		} catch (X86ParsingException e) {
			throw new X86ParsingException(e.getMessage(),
					instMatcher.start("operands")+e.getStartIndex(),
					instMatcher.start("operands")+e.getEndIndex());
		}

		if (operands.size() != instrType.numOperands()) {
			throw new X86ParsingException("too many operands",
											instMatcher.start("operands"),
											instr.length());
		}
		else if (instrType.numOperands() == 2) {
			if (operands.get(1) instanceof ConstantOperand) {
				// FIXME: start/end index is not right here
				throw new X86ParsingException("destination cannot be a constant",
												instMatcher.start("operands"),
												instr.length());
			}
			return new x86BinaryInstruction(instrName.substring(0, instrName.length()-1), 
					operands.get(0),
					operands.get(1),
					opSize);
		}
		else if (instrType.numOperands() == 1) {
			if (!instrName.startsWith("set")) 
				instrName = instrName.substring(0, instrName.length()-1);

			// TODO: throw exception if destination is a constant

			return new x86UnaryInstruction(instrName,
											operands.get(0),
											opSize);
		}
		else {
			throw new X86ParsingException("unsupported instruction type", 0, instrName.length());
		}

	}

	/**
	 * Returns the names of the registers used by this instruction.
	 * This includes any implicit registers used (e.g. %rsp by push and pop)
	 *
	 * @return Set containing names of registers used by this instruction.
	 */
    public abstract Set<String> getUsedRegisters();
	public abstract String toString();
}

/**
 * Class representing an x86 instruction with a single operand.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class x86UnaryInstruction extends x86Instruction {

    /**
     * The function that this instruction performs.
     */
    private UnaryX86Operation operation;

    /**
     * @param instType String representation of the instruction's operation.
     * @param destOp Operand representing the destination of the instruction.
     * @param size Number of bytes this instruction works on.
     */
    public x86UnaryInstruction(String instType, Operand destOp, OpSize size) {
        this.destination = destOp;
        this.opSize = size;

        Map<String, Boolean> flags = new HashMap<String, Boolean>();

        switch (instType) {
            case "inc":
                this.type = InstructionType.INC;
                this.operation
                        = (state, dest) -> {
                            BigInteger result = dest.getValue(state).add(BigInteger.ONE);
                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "dec":
                this.type = InstructionType.DEC;
                this.operation
                        = (state, dest) -> {
                            BigInteger result = dest.getValue(state).subtract(BigInteger.ONE);
                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "neg":
                this.type = InstructionType.NEG;
                this.operation
                        = (state, dest) -> {
                            BigInteger orig = dest.getValue(state);
                            BigInteger result = orig.negate();
                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("cf", orig.compareTo(BigInteger.ZERO) != 0);

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "not":
                this.type = InstructionType.NOT;
                this.operation
                        = (state, dest) -> {
                            BigInteger result = dest.getValue(state).not();
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "sete":
            case "setne":
            case "sets":
            case "setns":
            case "setg":
            case "setge":
            case "setl":
            case "setle":
                final Predicate<MachineState> p;
                switch (instType) {
                    case "sete":
                        this.type = InstructionType.SETE;
                        p = state -> state.getZeroFlag();
                        break;
                    case "setne":
                        this.type = InstructionType.SETNE;
                        p = state -> !state.getZeroFlag();
                        break;
                    case "sets":
                        this.type = InstructionType.SETS;
                        p = state -> state.getSignFlag();
                        break;
                    case "setns":
                        this.type = InstructionType.SETNS;
                        p = state -> !state.getSignFlag();
                        break;
                    case "setg":
                        this.type = InstructionType.SETG;
                        p = state -> !(state.getSignFlag() ^ state.getOverflowFlag()) & !state.getZeroFlag();
                        break;
                    case "setge":
                        this.type = InstructionType.SETGE;
                        p = state -> !(state.getSignFlag() ^ state.getOverflowFlag());
                        break;
                    case "setl":
                        this.type = InstructionType.SETL;
                        p = state -> (state.getSignFlag() ^ state.getOverflowFlag());
                        break;
                    case "setle":
                        this.type = InstructionType.SETLE;
                        p = state -> (state.getSignFlag() ^ state.getOverflowFlag()) | state.getZeroFlag();
                        break;
                    default:
                        p = null;
                        System.err.println("ERROR: set that isn't a set: " + instType);
                        System.exit(1);
                }
                this.operation
                        = (state, dest) -> {
                            BigInteger result = p.test(state) ? BigInteger.ONE : BigInteger.ZERO;
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "push":
                this.type = InstructionType.PUSH;
                this.operation
                        = (state, src) -> {
                            // step 1: subtract 8 from rsp
                            RegOperand rsp = new RegOperand("rsp", OpSize.QUAD);
                            MachineState tmp = rsp.updateState(state, Optional.of(rsp.getValue(state).subtract(BigInteger.valueOf(8))), flags);

                            // step 2: store src operand value in (%rsp)
                            MemoryOperand dest = new MemoryOperand("rsp", null, 1, 0, this.opSize);
                            return dest.updateState(tmp, Optional.of(src.getValue(tmp)), flags);
                        };
                break;
            case "pop":
                this.type = InstructionType.POP;
                this.operation
                        = (state, dest) -> {
                            // step 1: store (%rsp) value in dest operand 
                            MemoryOperand src = new MemoryOperand("rsp", null, 1, 0, this.opSize);
                            MachineState tmp = dest.updateState(state, Optional.of(src.getValue(state)), flags);

                            // step 2: add 8 to rsp
                            RegOperand rsp = new RegOperand("rsp", OpSize.QUAD);
                            return rsp.updateState(tmp, Optional.of(rsp.getValue(tmp).add(BigInteger.valueOf(8))), flags);

                        };
                break;
            default:
                System.err.println("invalid instr type for unary inst: " + instType);
                System.exit(1);
        }
    }

    @Override
    public MachineState eval(MachineState state) {
        return operation.apply(state, this.destination);
    }

    @Override
    public Set<String> getUsedRegisters() {
        return destination.getUsedRegisters();
    }
    
    @Override
    public String toString() {
        return type + " " + destination.toString();
    }
}

/**
 * Class representing an x86 instruction with two operands.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class x86BinaryInstruction extends x86Instruction {

    /**
     * An operand used solely as a source for the operation.
     */
    private Operand source;

    /**
     * The function performed by this instruction.
     */
    private BinaryX86Operation operation;

    // TODO: instType should be of type InstructionType
    /**
     * @param instType String representation of the instruction's operation.
     * @param srcOp A source operand of the instruction.
     * @param destOp Operand representing the destination of the instruction.
     * @param size Number of bytes this instruction works on.
     */
    public x86BinaryInstruction(String instType, Operand srcOp, Operand destOp, OpSize size) {
        this.source = srcOp;
        this.destination = destOp;
        this.opSize = size;

        Map<String, Boolean> flags = new HashMap<String, Boolean>();

        switch (instType) {
            case "add":
                this.type = InstructionType.ADD;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger src1 = dest.getValue(state);
                            BigInteger src2 = src.getValue(state);
                            BigInteger result = src1.add(src2);

                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("cf", false); // FIXME: implement
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "sub":
                this.type = InstructionType.SUB;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger src1 = dest.getValue(state);
                            BigInteger src2 = src.getValue(state);
                            BigInteger result = src1.subtract(src2);

                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("cf", false); // FIXME: implement
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "cmp":
                this.type = InstructionType.CMP;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger src1 = dest.getValue(state);
                            BigInteger src2 = src.getValue(state);
                            BigInteger result = src1.subtract(src2);

                            flags.put("of", (result.bitLength() + 1) > this.opSize.numBits());

                            // truncate if we are too long
                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("cf", false); // FIXME: implement
                            return dest.updateState(state, Optional.empty(), flags);
                        };
                break;
            case "xor":
                this.type = InstructionType.XOR;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger result = dest.getValue(state).xor(src.getValue(state));
                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("of", false);
                            flags.put("cf", false);
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "or":
                this.type = InstructionType.OR;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger result = dest.getValue(state).or(src.getValue(state));
                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("of", false);
                            flags.put("cf", false);
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "and":
                this.type = InstructionType.AND;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger result = dest.getValue(state).and(src.getValue(state));
                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("of", false);
                            flags.put("cf", false);
                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "test":
                this.type = InstructionType.TEST;
                this.operation
                        = (state, src, dest) -> {
                            BigInteger result = dest.getValue(state).and(src.getValue(state));
                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);
                            flags.put("of", false);
                            flags.put("cf", false);
                            return dest.updateState(state, Optional.empty(), flags);
                        };
                break;
            case "sal":
            case "shl":
                this.type = InstructionType.SAL;
                this.operation
                        = (state, src, dest) -> {
                            int shamt = src.getValue(state).intValue() % 32; // max shift amount is 31
                            BigInteger orig = dest.getValue(state);
                            BigInteger result = orig.shiftLeft(shamt);

                            int msbIndex = this.opSize.numBits() - 1;

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);

                            if (shamt > 0 && (msbIndex + 1) >= shamt) {
                                flags.put("cf", orig.testBit((msbIndex + 1) - shamt));
                            } else if ((msbIndex + 1) >= shamt) {
                                flags.put("cf", false); // TODO: not sure if this is handled correctly
                            }
                            // overflow is only defined when shifting by 1
                            if (shamt == 1) {
                                flags.put("of", orig.testBit(msbIndex) != orig.testBit(msbIndex - 1));
                            } else {
                                // This is an undefined case... false sounds great
                                // doesn't it?
                                flags.put("of", false);
                            }

                            byte[] resArray = result.toByteArray();
                            if (resArray.length > this.opSize.numBytes()) {
                                byte[] ba = Arrays.copyOfRange(resArray, 1, resArray.length);
                                result = new BigInteger(ba);
                            }

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "sar":
                this.type = InstructionType.SAR;
                this.operation
                        = (state, src, dest) -> {
                            int shamt = src.getValue(state).intValue() % 32; // max shift amount is 31
                            BigInteger orig = dest.getValue(state);
                            BigInteger result = orig.shiftRight(shamt);

                            if (result.bitLength() + 1 > this.opSize.numBits()) {
                                System.err.println("ERROR: shifting right made it bigger???");
                                System.exit(1);
                            }

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);

                            // overflow is false if shifting by 1, otherwise
                            // undefined
                            if (shamt == 1) {
                                flags.put("of", false);
                            } else {
                                // This is an undefined case... false sounds great
                                // doesn't it?
                                flags.put("of", false);
                            }

                            // shift by zero means CF isn't changed
                            if (shamt > 0) {
                                flags.put("cf", orig.testBit(shamt - 1));
                            }

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "shr":
                this.type = InstructionType.SHR;
                this.operation
                        = (state, src, dest) -> {
                            int shamt = src.getValue(state).intValue() % 32; // max shift amount is 31
                            BigInteger orig = dest.getValue(state);

                            // BigInteger doesn't have logical right shift (>>>)
                            // so we do the ugly thing here and use the >>>
                            // operator by converting to the native types of the
                            // operators.
                            String s = null;
                            switch (this.opSize) {
                                case BYTE:
                                    byte b = orig.byteValue();
                                    b = (byte) (b >>> shamt);
                                    s = "" + b;
                                    break;
                                case WORD:
                                    short w = orig.shortValue();
                                    w = (short) (w >>> shamt);
                                    s = "" + w;
                                    break;
                                case LONG:
                                    int l = orig.intValue();
                                    l = l >>> shamt;
                                    s = "" + l;
                                    break;
                                case QUAD:
                                    long q = orig.longValue();
                                    q = q >>> shamt;
                                    s = "" + q;
                                    break;
                            }
                            BigInteger result = new BigInteger(s);

                            int signum = result.signum();
                            flags.put("zf", signum == 0);
                            flags.put("sf", signum == -1);

                            // overflow is the most sig bit of original if shifting by 1, otherwise
                            // undefined
                            if (shamt == 1) {
                                flags.put("of", orig.testBit(this.opSize.numBytes() * 8 - 1));
                            } else {
                                // This is an undefined case... false sounds great
                                // doesn't it?
                                flags.put("of", false);
                            }

                            if (shamt > 0) {
                                // shift by zero means CF isn't changed
                                flags.put("cf", orig.testBit(shamt - 1));
                            }

                            return dest.updateState(state, Optional.of(result), flags);
                        };
                break;
            case "mov":
                this.type = InstructionType.MOV;
                this.operation
                        = (state, src, dest) -> dest.updateState(state, Optional.of(src.getValue(state)), flags);
                break;
            case "lea":
                this.type = InstructionType.LEA;
                this.operation
                        = (state, src, dest) -> {
                            // TODO: Use polymorophism to avoid this instanceof junk
                            if (!(src instanceof MemoryOperand)) {
                                System.err.println("ERROR: lea src must be a memory operand");
                                return null;
                            }

                            MemoryOperand mo = (MemoryOperand) src;
                            return dest.updateState(state, Optional.of(BigInteger.valueOf(mo.calculateAddress(state))), flags);
                        };
                break;
            default:
                System.err.println("unknown instr type for binary inst: " + instType);
                System.exit(1);
        }
    }

    @Override
    public MachineState eval(MachineState state) {
        return operation.apply(state, this.source, this.destination);
    }

    @Override
    public Set<String> getUsedRegisters() {
        Set<String> sourceRegs = source.getUsedRegisters();
        Set<String> destRegs = destination.getUsedRegisters();
        sourceRegs.addAll(destRegs);
        return sourceRegs;
    }
    
    @Override
    public String toString() {
        return type + " " + source.toString() + ", " + destination.toString();
    }
}

/**
 * The type of an x86 Instruction.
 */
enum InstructionType {
    ADD(2),
    SUB(2),
    CMP(2),
    OR(2),
    AND(2),
    TEST(2),
    XOR(2),
    SHL(2),
    SAL(2),
    SHR(2),
    SRL(2),
    SAR(2),
    MOV(2),
    LEA(2),
    INC(1),
    DEC(1),
    NEG(1),
    NOT(1),
    PUSH(1),
    POP(1),
    SETE(1),
    SETNE(1),
    SETS(1),
    SETNS(1),
    SETG(1),
    SETGE(1),
    SETL(1),
    SETLE(1);
    // TODO: set instructions for unsigned (e.g. seta)

    /**
     * Number of operands used by the instruction.
     */
    private int numOperands;

    private InstructionType(int nO) {
        this.numOperands = nO;
    }

    public int numOperands() {
        return this.numOperands;
    }
}

/**
 * Representation of the size of an x86 instruction or operand.
 */
enum OpSize {
    BYTE(1),
    WORD(2),
    LONG(4),
    QUAD(8);

    /**
     * The number of bytes used for this op.
     */
    private int numBytes;

    private OpSize(int nb) {
        this.numBytes = nb;
    }

    public int numBytes() {
        return this.numBytes;
    }

    public int numBits() {
        return this.numBytes * 8;
    }
}

/**
 * An abstract class representing an x86 operand.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
abstract class Operand {

    /**
     * @param state The state of the machine.
     * @return The value of the operand in a machine with the given state.
     */
    public abstract BigInteger getValue(MachineState state);

    /**
     * @param currState The current state of the machine.
     * @param val The value to update the operand with.
     * @param flags The condition flags to be set in the new state.
     * @return The state after updating the current state with the new value for
     * the operand.
     */
    public abstract MachineState updateState(MachineState currState, Optional<BigInteger> val, Map<String, Boolean> flags);

	/**
	 * Returns the names of the registers used by this operand.
	 *
	 * @return Set containing names of registers used by this operand.
	 */
    public abstract Set<String> getUsedRegisters();
}

/**
 * A class representing an x86-64 register operand (e.g. %eax).
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class RegOperand extends Operand {

    /**
     * The name of the register, sans % (e.g. "eax")
     */
    private String regName;

    /**
     * The size of the operand.
     */
    private OpSize opSize;

    public RegOperand(String regName, OpSize opSize) {
        this.regName = regName;
        this.opSize = opSize;
    }

    @Override
    public BigInteger getValue(MachineState state) {
        return state.getRegisterValue(regName);
    }

    @Override
    public MachineState updateState(MachineState currState, Optional<BigInteger> val, Map<String, Boolean> flags) {
        return currState.getNewState(this.regName, val, flags);
    }

    @Override
    public Set<String> getUsedRegisters(){ 
        HashSet<String> s = new HashSet<String>();
        s.add(MachineState.getQuadName(regName));
        return s;
    }
    
    @Override
    public String toString() {
        return "%" + regName;
    }
}

/**
 * A class representing an x86-64 memory operand.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class MemoryOperand extends Operand {

    /**
     * Name of the base register.
     */
    private String baseReg;

    /**
     * Name of the index register.
     */
    private String indexReg;

    /**
     * The scaling factor for the index register.
     */
    private int scale;

    /**
     * The offset amount.
     */
    private int offset;

    /**
     * The size of the operand.
     */
    private OpSize opSize;

    public MemoryOperand(String baseReg, String indexReg, int scale, int offset, OpSize opSize) {
        this.baseReg = baseReg;
        this.indexReg = indexReg;
        this.scale = scale;
        this.offset = offset;
        this.opSize = opSize;
    }

    /**
     * Calculate the effective address of the operand, given the specified
     * machine state.
     *
     * @param state The state in which to calculate the address.
     * @return The effective address.
     */
    public long calculateAddress(MachineState state) {
        /**
         * @tricky should this return BigInteger
         */
        long address = state.getRegisterValue(baseReg).add(BigInteger.valueOf(offset)).longValue();
        if (indexReg != null) {
            address += state.getRegisterValue(indexReg).multiply(BigInteger.valueOf(scale)).longValue();
        }

        return address;
    }

    @Override
    public BigInteger getValue(MachineState state) {
        return state.getMemoryValue(calculateAddress(state), opSize.numBytes());
    }

    @Override
    public MachineState updateState(MachineState currState, Optional<BigInteger> val, Map<String, Boolean> flags) {
        return currState.getNewState(calculateAddress(currState), val, opSize.numBytes(), flags);
    }
    
    @Override
    public Set<String> getUsedRegisters() {
        HashSet<String> s = new HashSet<String>();
        if (baseReg != null) // TODO: OPTIONAL!!!
            s.add(baseReg);
        if (indexReg != null)
            s.add(indexReg);
        return s;
    }

    @Override
    public String toString() {
        String res = offset + "(%" + baseReg;
        if (indexReg != null) {
            res += ", %" + indexReg + ", " + scale;
        }

        res += ")";
        return res;
    }
}

/**
 * A class representing an x86-64 constant (i.e. immediate) operand.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class ConstantOperand extends Operand {

    /**
     * The operand's value.
     */
    private long constant;

    public ConstantOperand(long val) {
        this.constant = val;
    }

    @Override
    public BigInteger getValue(MachineState state) {
        return BigInteger.valueOf(constant);
    }

    @Override
    public MachineState updateState(MachineState currState, Optional<BigInteger> val, Map<String, Boolean> flags) {
        System.err.println("Why are you trying to set a constant?");
        // TODO: exception here?
        return currState;
    }
    
    @Override
    public Set<String> getUsedRegisters() {
        HashSet<String> s = new HashSet<String>();
        return s;
    }

    @Override
    public String toString() {
        return "$" + constant;
    }
}


@FunctionalInterface
interface BinaryX86Operation {

    MachineState apply(MachineState state, Operand src, Operand dest);
}

@FunctionalInterface
interface UnaryX86Operation {

    MachineState apply(MachineState state, Operand dest);
}

/**
 * Simple class to test x86 instruction parsing and evaluation.
 *
 * @author Sat Garcia (sat@sandiego.edu)
 */
class x86InstructionTester {
	public static void main(String[] args) {
		ArrayList<x86Instruction> instructions = new ArrayList<x86Instruction>();

		try {
			instructions.add(x86Instruction.parseInstruction("movq $9, %rax"));
			instructions.add(x86Instruction.parseInstruction("movq $4, %rbx"));
			instructions.add(x86Instruction.parseInstruction("addq %rax, %rbx"));
			instructions.add(x86Instruction.parseInstruction("pushq %rbx"));
			instructions.add(x86Instruction.parseInstruction("popq %rcx"));
			instructions.add(x86Instruction.parseInstruction("leaq -12(%rsp), %rdx"));
			instructions.add(x86Instruction.parseInstruction("movl $73, (%rdx)"));
			instructions.add(x86Instruction.parseInstruction("incl %esi"));
			instructions.add(x86Instruction.parseInstruction("decl %edi"));

			// test that smaller register only affect part of the whole register
			instructions.add(x86Instruction.parseInstruction("movl $0, %edx"));
			instructions.add(x86Instruction.parseInstruction("movw $-1, %dx"));
			instructions.add(x86Instruction.parseInstruction("movb $2, %dl"));
			instructions.add(x86Instruction.parseInstruction("movb $3, %dh"));

			// tests for condition codes
			instructions.add(x86Instruction.parseInstruction("movl $0, %ebp"));
			instructions.add(x86Instruction.parseInstruction("movl $1, %ebp"));
			instructions.add(x86Instruction.parseInstruction("sall $31, %ebp"));
			instructions.add(x86Instruction.parseInstruction("decl %ebp"));
			instructions.add(x86Instruction.parseInstruction("addl $0, %ebp"));
			instructions.add(x86Instruction.parseInstruction("incl %ebp"));
			instructions.add(x86Instruction.parseInstruction("negl %ebp"));
			instructions.add(x86Instruction.parseInstruction("andl $0, %ebp"));
			instructions.add(x86Instruction.parseInstruction("notl %ebp"));
			instructions.add(x86Instruction.parseInstruction("shrl $1, %ebp"));

			// more LONG registers
			instructions.add(x86Instruction.parseInstruction("movl $1, %r8d"));
			instructions.add(x86Instruction.parseInstruction("sall $4, %r8d"));
			instructions.add(x86Instruction.parseInstruction("sarl $3, %r8d"));

			// tests for cmp, test, and set instructions
			instructions.add(x86Instruction.parseInstruction("movl $-5, %eax"));
			instructions.add(x86Instruction.parseInstruction("cmpl $-5, %eax"));
			instructions.add(x86Instruction.parseInstruction("setge %bl"));

			// TODO: more tests for cmp, test, and set instructions
		} catch (X86ParsingException e) {
			e.printStackTrace();
		}
		
		MachineState state = new MachineState();
		System.out.println(state);
		for (x86Instruction inst : instructions) {
			System.out.println(inst);
			state = inst.eval(state);
			System.out.println(state);
		}

		try {
			x86Instruction.parseInstruction("movl $-5, %eax");
			x86Instruction.parseInstruction("movl 0(%rax, %ecx, 13), %eax");
		} catch (X86ParsingException e) {
			e.printStackTrace();
		}

	}
}
