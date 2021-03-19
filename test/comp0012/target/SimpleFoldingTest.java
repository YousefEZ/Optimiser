package comp0012.target;

import org.junit.Test;
import org.junit.After;
import org.junit.Before;

import static org.junit.Assert.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * test simple folding
 */
public class SimpleFoldingTest {

    SimpleFolding sf = new SimpleFolding();
    
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    
    @Before
    public void setUpStreams()
    {
        System.setOut(new PrintStream(outContent));
    }
    
    @After
    public void cleanUpStreams()
    {
        System.setOut(null);
    }

    @Test
    public void testSimple(){
        sf.simple();
        assertEquals("12412\n", outContent.toString());
    }

}
