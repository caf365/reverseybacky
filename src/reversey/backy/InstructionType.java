package reversey.backy;

/**
 * The type of an x86 Instruction.
 */
public enum InstructionType {
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
    SETLE(1),
    JE(1),
    JNE(1),
    JS(1),
    JNS(1),
    JG(1),
    JGE(1),
    JL(1),
    JLE(1),
    JMP(1);
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