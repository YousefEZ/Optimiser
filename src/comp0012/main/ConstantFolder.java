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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Stack;

/** @noinspection WeakerAccess */
public class ConstantFolder {

    /** @noinspection WeakerAccess */
    ClassParser parser = null;
    /** @noinspection WeakerAccess */
    ClassGen gen = null;

    /** @noinspection WeakerAccess */
    JavaClass original = null;
    /** @noinspection WeakerAccess */
    JavaClass optimized = null;

    private ClassGen cgen;
    private ConstantPoolGen cpgen;
    private Stack<Number> stack;
    private Stack<InstructionHandle> loadInstructions;
    private HashMap<Integer, Number> variables;

    private boolean deleteElseBranch;

    private boolean DEBUG;

    public ConstantFolder(String classFilePath) {
        try {
            this.parser = new ClassParser(classFilePath);
            this.original = this.parser.parse();
            this.gen = new ClassGen(this.original);
        } catch (IOException e) {
            e.printStackTrace();
        }

        DEBUG = true; // used to display logs about operations on instructions.
    }

    // <--------------------------------------- Display Methods (Helper) -------------------------------------------->

    private void displayLog(String log) {
        if (DEBUG) System.out.println(log);
    }

    private void displayNewMethod(String methodName){
        displayLog("------------------");
        displayLog("|| NEXT METHOD: ||");
        displayLog(methodName);
        displayLog("-----------------");
    }

    private void displayNextClass(){
        displayLog("-----------------");
        displayLog("||             ||");
        displayLog("|| NEXT CLASS  ||");
        displayLog("||             ||");
        displayLog("-----------------");
    }

    // <------------------------------------------------ Optimisation ------------------------------------------------->

    /** @noinspection WeakerAccess */
    public void optimize() {
        cgen = new ClassGen(original);
        cpgen = cgen.getConstantPool();

        stack = new Stack<Number>();
        variables = new HashMap<Integer, Number>();
        loadInstructions = new Stack<InstructionHandle>();

        // Implement your optimization here
        Method[] methods = cgen.getMethods(); // gets all the methods.
        System.out.println(cgen.getMethods());
        for (Method method : methods) {
            deleteElseBranch = false;
            displayNewMethod(method.getName());
            optimizeMethod(method); // optimizes each method.
            stack.clear(); // clears stack for next method.
            variables.clear(); // clears variables for next method.
        }
        PeepHole.optimise(cgen, cpgen);
        PeepHole.optimise(cgen, cpgen);

        displayNextClass();
        this.optimized = cgen.getJavaClass();
    }

    // replaces the original method code with the optimized method code.
    private void replaceMethodCode(Method originalMethod, MethodGen methodGen){
        methodGen.setMaxStack();
        methodGen.setMaxLocals();
        Method newMethod = methodGen.getMethod();
        cgen.replaceMethod(originalMethod, newMethod);
    }

    // <--------------------------------------------- Method Optimization --------------------------------------------->

    private void optimizeMethod(Method method) {
        Code methodCode = method.getCode(); // gets the code inside the method.
        InstructionList instructionList = new InstructionList(methodCode.getCode()); // gets code and makes an list of Instructions.
        MethodGen methodGen = new MethodGen(method.getAccessFlags(), method.getReturnType(), method.getArgumentTypes(),
                null, method.getName(), cgen.getClassName(), instructionList, cpgen);

        displayLog(instructionList.toString());

        for (InstructionHandle handle : instructionList.getInstructionHandles()) {
            handleInstruction(handle, instructionList);
        }
        System.out.println(instructionList);
        instructionList.setPositions(true);
        displayLog(instructionList.toString());
        replaceMethodCode(method, methodGen);
    }

    /** handles the instruction inside of the InstructionHandle by first checking its type then optimising it.
     *
     * @param handle wrapper that contains the instruction.
     * @param instructionList list of all the instruction, this is required because some changes are made here.
     */
    private void handleInstruction(InstructionHandle handle, InstructionList instructionList){
        Instruction instruction = handle.getInstruction(); // gets the instruction from the instruction handle.
        //displayLog(instruction.toString());

        // Load Instructions
        if (isLoadConstantValueInstruction(instruction)) handleLoad(handle);
        else if (instruction instanceof LoadInstruction) handleVariableLoad(handle);

        if (instruction instanceof ConversionInstruction) handleConversion(instructionList, handle);

        // Store Instructions
        if (instruction instanceof StoreInstruction) handleStore(handle);

        // Comparison Instructions
        if (instruction instanceof ArithmeticInstruction) handleArithmetic(handle, instructionList);
        if (instruction instanceof LCMP) handleLongComparison(handle, instructionList);
        else if (instruction instanceof IfInstruction) handleComparison(handle, instructionList);

        if (instruction instanceof GotoInstruction) handleGoTo(handle, instructionList);
    }

    // checks if the Instruction Loads a constant value.
    private boolean isLoadConstantValueInstruction(Instruction instruction){
        return (instruction instanceof LDC || instruction instanceof LDC2_W ||
                instruction instanceof SIPUSH || instruction instanceof BIPUSH ||
                instruction instanceof ICONST || instruction instanceof FCONST ||
                instruction instanceof DCONST || instruction instanceof LCONST);
    }

    // <------------------------------------------ Handling Instructions --------------------------------------------->

    private void handleConversion(InstructionList instructionList, InstructionHandle handle) {
        System.out.println("CONVERTING TOP OF STACK");
        stack.push(convertValue(handle.getInstruction(), stack.pop()));
        removeHandle(instructionList, handle);
    }

    private void handleGoTo(InstructionHandle handle, InstructionList instructionList) {
        if (deleteElseBranch){
            deleteElseBranch = false;
            GotoInstruction instruction = (GotoInstruction) handle.getInstruction();
            InstructionHandle targetHandle = instruction.getTarget();
            removeHandle(instructionList, handle, targetHandle.getPrev());
        }
    }

    // Method that handles Long Comparisons.
    private void handleLongComparison(InstructionHandle handle, InstructionList instructionList) {
        long first = (Long) stack.pop();
        long second = (Long) stack.pop();

        int result = first >= second ? 1 : -1;
        removePreviousTwoLoadInstructions(instructionList);
        handle.setInstruction(createLoadInstruction(result));
        loadInstructions.push(handle);
        stack.push(result);
        //removeHandle(instructionList, handle);
    }

    // Method that handles comparison instructions.
    private void handleComparison(InstructionHandle handle, InstructionList instructionList) {
        boolean outcome = parseComparisonInstruction(handle.getInstruction());
        IfInstruction instruction = (IfInstruction) handle.getInstruction();
        deleteElseBranch = false;
        if (outcome) {
            InstructionHandle targetHandle = instruction.getTarget();
            removeHandle(instructionList, handle, targetHandle.getPrev());
        } else {
            removeHandle(instructionList, handle);
            deleteElseBranch = true;
        }
        //instructionList.insert(createLoadInstruction(outcome ? 1 : 0));
        //removePreviousTwoLoadInstructions(instructionList);
        //removeHandle(instructionList, handle);
    }

    // Method that handles storing values into a variable.
    private void handleStore(InstructionHandle handle) {
        displayLog("STORE INSTRUCTION DETECTED");
        Number value = stack.pop();
        loadInstructions.pop();
        displayLog("STORING VALUE: " + value);
        int index = ((StoreInstruction) handle.getInstruction()).getIndex();
        variables.put(index, value);
    }

    // Method that handles loading in values from variables.
    private void handleVariableLoad(InstructionHandle handle) {
        displayLog("LOADING VARIABLE");
        Number variableValue = variables.get(((LoadInstruction) handle.getInstruction()).getIndex());
        stack.push(variableValue);
        loadInstructions.push(handle);
    }

    // Method that handles loading in values from a LoadInstruction.
    private void handleLoad(InstructionHandle handle) {
        displayLog("LOAD INSTRUCTION DETECTED");
        Number nextValue = getInstructionConstant(handle.getInstruction()); // retrieves the value from the load instruction.
        displayLog("LOADED VALUE: " + nextValue);
        stack.push(nextValue); // pushes the value retrieved from the load instruction onto the stack.
        loadInstructions.push(handle); // pushes the load instruction onto the stack.
    }

    // Method that handles an arithmetic operation and loads it in to the instruction list.
    private void handleArithmetic(InstructionHandle handle, InstructionList instructionList) {
        performArithmeticOperation(handle.getInstruction());
        condenseOperationInstructions(instructionList, handle, stack.peek()); // using peek because it needs to be in stack.
    }

    private void removeHandle(InstructionList instructionList, InstructionHandle handle) {
        if (handle == null) return;
        try {
            instructionList.delete(handle);
        } catch (TargetLostException e) {
            InstructionHandle[] targets = e.getTargets();

            for (InstructionHandle target : targets) {
                InstructionTargeter[] targeters = target.getTargeters();

                for (InstructionTargeter targeter : targeters) targeter.updateTarget(target, null);
            }
        }
    }

    private void removeHandle(InstructionList instructionList, InstructionHandle handle, InstructionHandle targetHandle) {
        System.out.println("DELETING ALL BETWEEN: " + handle + " TO " + targetHandle);
        try {
            instructionList.delete(handle, targetHandle);
        } catch (TargetLostException ignored){
            System.out.println("UNABLE TO DELETE ALL");
        }

    }

    // <------------------------------------------------ Helper Methods ----------------------------------------------->

    private Number convertValue(Instruction instruction, Number value) {
        if (instruction instanceof I2D || instruction instanceof L2D || instruction instanceof F2D){
            return value.doubleValue();
        } else if (instruction instanceof I2F || instruction instanceof L2F || instruction instanceof D2F){
            return value.doubleValue();
        } else if (instruction instanceof I2L || instruction instanceof D2L || instruction instanceof F2L){
            return value.doubleValue();
        } else if (instruction instanceof D2I || instruction instanceof F2I || instruction instanceof L2I){
            return value.doubleValue();
        }
        throw new IllegalStateException("Instruction not recognised");
    }

    /** used when performing an operation such as arithmetic or comparison, to basically reduce 3 instructions to 1.
     * 3 instructions being: LOAD X, LOAD Y, OPERATION. into just: LOAD Z, where Z is the result.
     *
     * @param instructionList list of instructions in the method, used to delete the unneeded load statements.
     * @param handle instruction wrapper that contains the instruction that performs the operation.
     * @param value the resultant value from the operation, that requires a Load Instruction.
     */
    private void condenseOperationInstructions(InstructionList instructionList, InstructionHandle handle, Number value) {
        removePreviousTwoLoadInstructions(instructionList); // remove the 2 LOAD Instructions
        switchInstructionToLoadNumber(handle, value); // creates a load instruction that replaces the operation.
    }

    /** creates a load instruction using the value argument given, and replaces the instruction in handle with it.
     *
     * @param handle an instruction wrapper that holds the instruction to be replaced.
     * @param value a value that the LoadInstruction will contain (i.e. LOAD value)
     */
    private void switchInstructionToLoadNumber(InstructionHandle handle, Number value){
        Instruction loadInstruction = createLoadInstruction(value);
        assert loadInstruction != null;
        System.out.println("SWITCHING INSTRUCTION: " + handle.getInstruction() + " WITH: " + loadInstruction);
        handle.setInstruction(loadInstruction);
        loadInstructions.push(handle);
    }

    /** pops the load instructions from the stack, and using that to reference the instructions that need to get deleted.
     * this method is primarily used to remove the load instructions that were used for operations (Arithmetic/Comparison).
     *
     * @param instructionList InstructionList instance that holds all the instructions for a method.
     */
    private void removePreviousTwoLoadInstructions(InstructionList instructionList) {
        displayLog("REMOVING LOAD INSTRUCTION");

        InstructionHandle instruction = loadInstructions.pop();
        displayLog(instruction.toString());
        removeHandle(instructionList, instruction);

        instruction = loadInstructions.pop();
        displayLog(instruction.toString());
        removeHandle(instructionList, instruction);
    }

    /** takes in a Comparison Instruction, and creates the boolean value using the values in the stack.
     *
     * @param instruction a Comparison Instruction, which is used to figure out which comparison to do.
     * @return the result of the Comparison Instruction.
     */
	private boolean parseComparisonInstruction(Instruction instruction){
    	// comparisons with the integer value 0.
        System.out.println("SCANNING THROUGH SPECIAL COMPARISONS");
    	if (instruction instanceof IFLE) return (Integer) stack.pop() <= 0;
		else if (instruction instanceof IFLT) return (Integer) stack.pop() < 0;
		else if (instruction instanceof IFGE) return (Integer) stack.pop() >= 0;
		else if (instruction instanceof IFGT) return (Integer) stack.pop() > 0;
		else if (instruction instanceof IFEQ) return (Integer) stack.pop() == 0;
		else if (instruction instanceof IFNE) return (Integer) stack.pop() != 0;

		// comparisons with integers.
        System.out.println("SCANNING THROUGH REGULAR COMPARISONS");
		int first = (Integer) stack.pop();
		int second = (Integer) stack.pop();

		if (instruction instanceof IF_ICMPLE) return first <= second;
		else if (instruction instanceof IF_ICMPLT) return first < second;
		else if (instruction instanceof IF_ICMPGE) return first >= second;
		else if (instruction instanceof IF_ICMPGT) return first > second;
		else if (instruction instanceof IF_ICMPEQ) return first == second;
		else if (instruction instanceof IF_ICMPNE) return first != second;

		throw new IllegalStateException(String.valueOf(instruction)); // if it is None of these objects then error.
	}

    /** This method creates a load instruction using the value that was given to it.
     *
     * @param value a Number object that represents a value.
     * @return an Load Instruction that loads the given number value.
     */
	private Instruction createLoadInstruction(Number value){
		if (value instanceof Double){
			return new LDC2_W(cpgen.addDouble((Double) value)); // pushes double
		} else if (value instanceof Integer){
		    int int_value = (Integer) value;
		    if (int_value >= -1 && int_value <= 5) return new ICONST(int_value);
			return new LDC(cpgen.addInteger((Integer) value)); // pushes integer.
		} else if (value instanceof Long){
			return new LDC2_W(cpgen.addLong((Long) value)); // pushes long
		} else if (value instanceof Float){
			return new LDC(cpgen.addFloat((Float) value)); // pushes float.
		}
		return null;
	}


    /** Gets the value that is to be loaded from a load instruction.
	 *
	 * @param nextInstruction the LoadInstruction that holds the value to be loaded.
	 * @return a Number object that represents the value.
	 */
	private Number getInstructionConstant(Instruction nextInstruction) {
		if (nextInstruction instanceof LDC) {
			return (Number) ((LDC) nextInstruction).getValue(cpgen);

		} else if (nextInstruction instanceof LDC2_W) {
			return ((LDC2_W) nextInstruction).getValue(cpgen);

		} else if (nextInstruction instanceof BIPUSH) {
			return ((BIPUSH) nextInstruction).getValue();

		} else if (nextInstruction instanceof SIPUSH) {
			return ((SIPUSH) nextInstruction).getValue();

		} else if (nextInstruction instanceof ICONST){
			return ((ICONST) nextInstruction).getValue();

		} else if (nextInstruction instanceof FCONST){
			return ((FCONST) nextInstruction).getValue();

		} else if (nextInstruction instanceof DCONST){
			return ((DCONST) nextInstruction).getValue();

		} else if (nextInstruction instanceof LCONST){
			return ((LCONST) nextInstruction).getValue();
		}

		return null;
	}

	/**Performs an arithmetic operation using the popping the first 2 values in the stack, and pushing the combined val.
	 *
	 * @param nextInstruction the instruction that indicates the type of arithmetic operation.
	 */
	private void performArithmeticOperation(Instruction nextInstruction) {
		Number second = stack.pop();
		Number first = stack.pop();

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

		displayLog("CALCULATED -> " + combinedValue);
		// if null then arithmetic operation NOT RECOGNISED.
		if (combinedValue != null) stack.push(combinedValue);

	}

    /** @noinspection WeakerAccess */
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