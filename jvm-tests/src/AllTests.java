package tests;

public final class AllTests {
    public static void main(String[] args) throws Exception {
        boolean stressOnly = args.length > 0 && args[0].equals("stress");
        long t0 = System.currentTimeMillis();
        if (!stressOnly) {
            ModuleTests.run();
            MemoryTests.run();
            ChatTests.run();
            AttachmentTests.run();
            FileSnifferTests.run();
            CoderModuleTests.run();
            TavilyKeyTests.run();
            WebCoreTests.run();
            ChatWebTests.run();
            WebPhase2Tests.run();
            Phase3QuestionSetTest.run();
            AssistantRegression1000Test.run();
            Phase4Tests.run();
        }
        StressTest.run();
        System.out.println("\n================================================");
        System.out.println("checks: " + T.checks + "   failures: " + T.failures + "   time: " + (System.currentTimeMillis() - t0) + " ms");
        if (T.failures > 0) { System.out.println("RESULT: FAIL"); System.exit(1); }
        System.out.println("RESULT: PASS");
    }
}
