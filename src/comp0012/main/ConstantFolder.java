package comp0012.main;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Stack;


public class ConstantFolder {

	/** @noinspection WeakerAccess*/
    ClassParser parser = null;
	/** @noinspection WeakerAccess*/
    ClassGen gen = null;

    private ClassGen cgen;
    private ConstantPoolGen cpgen;
    private Stack<Number> stack;
    private HashMap<Integer, Number> variables;


    /** @noinspection WeakerAccess*/
	JavaClass original = null;
	/** @noinspection WeakerAccess*/
    JavaClass optimized = null;

    public ConstantFolder(String classFilePath) {
        try {
            this.parser = new ClassParser(classFilePath);
            this.original = this.parser.parse();
            this.gen = new ClassGen(this.original);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void optimize() {
    	cgen = new ClassGen(original);
        cpgen = cgen.getConstantPool();

		stack = new Stack<Number>();
		variables = new HashMap<Integer, Number>();

        // Implement your optimization here
		Method[] methods = cgen.getMethods(); // gets all the methods.
		for (Method method: methods){
			optimizeMethod(method); // optimizes each method
			stack.clear();
			variables.clear();
		}

        this.optimized = gen.getJavaClass();
    }

	private void optimizeMethod(Method method) {
		Code methodCode = method.getCode(); // gets the code inside the method.
		InstructionList instructionList = new InstructionList(methodCode.getCode()); // gets code and makes an list of Instructions.

		System.out.println("OPTIMIZING: " + method.getName());

		for (InstructionHandle handle: instructionList.getInstructionHandles()){
			Instruction nextInstruction = handle.getInstruction();
			System.out.println(nextInstruction);

			if (nextInstruction instanceof ArithmeticInstruction) handleArithmetic(handle, nextInstruction, instructionList);
			if (nextInstruction instanceof LoadInstruction) handleLoad(handle, nextInstruction, instructionList);
			if (nextInstruction instanceof StoreInstruction) handleStore(handle, nextInstruction, instructionList);

		}
	}

	private void handleStore(InstructionHandle handle, Instruction nextInstruction, InstructionList instructionList) {
		Number value = stack.pop();
		int index = ((StoreInstruction) nextInstruction).getIndex();
		variables.put(index, value);
		try {
			instructionList.delete(handle);
		} catch (TargetLostException e) {
			e.printStackTrace();
		}
	}

	private void createLoadInstruction(InstructionHandle handle, InstructionList instructionList, Number value){
		if (value instanceof Double){
			instructionList.insert(handle, new LDC2_W(cpgen.addDouble((Double) value))); // pushes double
		} else if (value instanceof Integer){
			instructionList.insert(handle, new LDC(cpgen.addInteger((Integer) value))); // pushes integer.
		} else if (value instanceof Long){
			instructionList.insert(handle, new LDC2_W(cpgen.addLong((Long) value))); // pushes long
		} else if (value instanceof Float){
			instructionList.insert(handle, new LDC(cpgen.addFloat((Float) value))); // pushes float.
		}
	}

	private void handleLoad(InstructionHandle handle, Instruction nextInstruction, InstructionList instructionList) {
		Number nextValue = getInstructionConstant(nextInstruction);
		createLoadInstruction(handle, instructionList, nextValue);

		try {
			instructionList.delete(handle);
		} catch (TargetLostException e) {
			e.printStackTrace();
		}
	}

	private Number getInstructionConstant(Instruction nextInstruction) {
		if (nextInstruction instanceof LDC) {
			return (Number) ((LDC) nextInstruction).getValue(cpgen);

		} else if (nextInstruction instanceof LDC2_W) {
			return ((LDC2_W) nextInstruction).getValue(cpgen);

		} else if (nextInstruction instanceof BIPUSH) {
			return ((BIPUSH) nextInstruction).getValue();

		} else if (nextInstruction instanceof SIPUSH) {
			return ((SIPUSH) nextInstruction).getValue();
		}
		return null;
	}

	/** Method that handles an arithmetic operation and loads it in to the instruction list.
	 *
	 * @param handle wrapper for the instruction
	 * @param nextInstruction holds the instruction
	 * @param instructionList the list of instructions in a method body.
	 */
	private void handleArithmetic(InstructionHandle handle, Instruction nextInstruction, InstructionList instructionList) {
		performArithmeticOperation(nextInstruction);

		Number nextValue = stack.pop();
		createLoadInstruction(handle, instructionList, nextValue);

		stack.push(nextValue);
		try {
			instructionList.delete(handle);
		} catch (TargetLostException e) {
			e.printStackTrace();
		}
	}

	/**Performs an arithmetic operation using the popping the first 2 values in the stack, and pushing the combined val.
	 *
	 * @param nextInstruction the instruction that indicates the type of arithmetic operation.
	 */
	private void performArithmeticOperation(Instruction nextInstruction) {
		Number first = stack.pop();
		Number second = stack.pop();

		Number combinedValue = null;

		// <------ Integer Operations ------>
		if (nextInstruction instanceof IADD){
			combinedValue = first.intValue() + second.intValue();
		} else if (nextInstruction instanceof ISUB){
			combinedValue = first.intValue() - second.intValue();
		} else if (nextInstruction instanceof IMUL){
			combinedValue = first.intValue() * second.intValue();
		} else if (nextInstruction instanceof IDIV){
			combinedValue = first.intValue() / second.intValue();
		}

		// <------ Double Operations ------>
		else if (nextInstruction instanceof DADD){
			combinedValue = first.doubleValue() + second.doubleValue();
		} else if (nextInstruction instanceof DSUB){
			combinedValue = first.doubleValue() - second.doubleValue();
		} else if (nextInstruction instanceof DMUL){
			combinedValue = first.doubleValue() * second.doubleValue();
		} else if (nextInstruction instanceof DDIV){
			combinedValue = first.doubleValue() / second.doubleValue();
		}

		// <------ Float Operations ------>
		else if (nextInstruction instanceof FADD){
			combinedValue = first.floatValue() + second.floatValue();
		} else if (nextInstruction instanceof FSUB){
			combinedValue = first.floatValue() - second.floatValue();
		} else if (nextInstruction instanceof FMUL){
			combinedValue = first.floatValue() * second.floatValue();
		} else if (nextInstruction instanceof FDIV){
			combinedValue = first.floatValue() / second.floatValue();
		}

		// <------ Long Operations ------>
		else if (nextInstruction instanceof LADD){
			combinedValue = first.longValue() + second.longValue();
		} else if (nextInstruction instanceof LSUB){
			combinedValue = first.longValue() - second.longValue();
		} else if (nextInstruction instanceof LMUL){
			combinedValue = first.longValue() * second.longValue();
		} else if (nextInstruction instanceof LDIV){
			combinedValue = first.longValue() / second.longValue();
		}

		// if null then arithmetic operation NOT RECOGNISED.
		if (combinedValue != null) stack.push(combinedValue);

	}


	public void write(String optimisedFilePath) {
        this.optimize();

        try {
            FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
            this.optimized.dump(out);
        } catch (FileNotFoundException e) {
            // Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // Auto-generated catch block
            e.printStackTrace();
        }
    }
}