package comp0012.target;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Test constant variable folding
 */
public class ConstantVariableFoldingTest {

    ConstantVariableFolding cvf = new ConstantVariableFolding();

    @Test
    public void testMethodOne(){
        assertEquals(3650, cvf.methodOne());
    }

    @Test
    public void testMethodTwo(){
        assertEquals(1.67, cvf.methodTwo(), 0.001);
    }

    @Test
    public void testMethodThree(){
        assertEquals(false, cvf.methodThree());
    }
    
    @Test
    public void testMethodFour(){
        assertEquals(true, cvf.methodFour());
    }
    

}
