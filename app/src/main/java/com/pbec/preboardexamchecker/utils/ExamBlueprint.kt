package com.pbec.preboardexamchecker.utils

data class TopicSpec(
    val topic: String,
    val numObjective: Int,
    val numComputation: Int,
    val total: Int = numObjective + numComputation,
    val aliases: List<String> = emptyList()
)

data class ExamBlueprint(
    val subject: String,
    val specs: List<TopicSpec>
) {
    val totalQuestions: Int = specs.sumOf { it.total }
}

object ExamBlueprints {
    val Mathematics = ExamBlueprint(
        subject = "Mathematics",
        specs = listOf(
            TopicSpec("Algebra and Complex Numbers", 2, 3, aliases = listOf("Algebra", "Complex")),
            TopicSpec("Trigonometry", 2, 3, aliases = listOf("Trig")),
            TopicSpec("Analytic Geometry", 1, 4, aliases = listOf("Analytic")),
            TopicSpec("Probability and Statistics", 1, 4, aliases = listOf("Prob", "Stat")),
            TopicSpec("Calculus 1", 5, 10, aliases = listOf("Diffcal", "Differential")),
            TopicSpec("Calculus 2", 4, 11, aliases = listOf("Integcal", "Integral")),
            TopicSpec("Engineering Data and Analysis", 6, 14, aliases = listOf("Data Analysis")),
            TopicSpec("Differential Equation", 5, 10, aliases = listOf("DE")),
            TopicSpec("Numerical Methods", 4, 11, aliases = listOf("Numerical"))
        )
    )

    val ESAS = ExamBlueprint(
        subject = "ESAS",
        specs = listOf(
            TopicSpec("Chemistry for Engineers", 2, 3, aliases = listOf("Chemistry", "Chem")),
            TopicSpec("Physics for Engineers", 5, 10, aliases = listOf("Physics")),
            TopicSpec("Computer Programming, Microprocessors, Logic Circuit and Switching Theory", 0, 0, 15, aliases = listOf("CP,M, LCST", "Computer", "Programming")),
            TopicSpec("Material Science and Environmental Science and Engineering", 0, 0, 5, aliases = listOf("Material Science", "M&E Science", "Environmental")),
            TopicSpec("Fluid Mechanics", 2, 3, aliases = listOf("Fluids", "Fluid")),
            TopicSpec("Fundamentals of Deformable Bodies", 2, 3, aliases = listOf("Deformable", "Strength")),
            TopicSpec("Basic Thermodynamics", 4, 1, aliases = listOf("Thermodynamics", "Thermo")),
            TopicSpec("EE Laws, Codes and Professional Ethics, BOSH and Electrical Standards", 0, 0, 20, aliases = listOf("Law", "BOSH", "Ethics", "Standards")),
            TopicSpec("Engineering Economics", 3, 12, aliases = listOf("Economics", "Econ")),
            TopicSpec("Techno & Management of Engineering Projects", 0, 0, 10, aliases = listOf("Techno", "Management", "Projects"))
        )
    )

    val ProfessionalEE = ExamBlueprint(
        subject = "Professional EE",
        specs = listOf(
            TopicSpec("Electromagnetism", 3, 7, aliases = listOf("EM")),
            TopicSpec("Electrical Circuits 1", 3, 7, aliases = listOf("DC")),
            TopicSpec("Electrical Circuits 2", 2, 8, aliases = listOf("AC")),
            TopicSpec("Fundamentals of Electronics Communication, Electronics 1 and 2", 0, 0, 5, aliases = listOf("Electronics")),
            TopicSpec("Electrical Apparatus and Industrial Electronics", 1, 4, aliases = listOf("Apparatus")),
            TopicSpec("Electrical Machine 1", 2, 3, aliases = listOf("Machine 1")),
            TopicSpec("Electrical Machine 2", 5, 10, aliases = listOf("Machine 2")),
            TopicSpec("Instrumentation & Control, Feedback Control System and Research Methods", 0, 0, 5, aliases = listOf("Control")),
            TopicSpec("Electrical System and Illumination Engineering Design", 2, 8, aliases = listOf("Design", "Illumination")),
            TopicSpec("Fund. of Power Plant Engineering Design and Distribution and Substation Design", 2, 3, aliases = listOf("Power Plant")),
            TopicSpec("Power System and Analysis", 7, 13, aliases = listOf("Power System"))
        )
    )

    fun getBlueprint(subject: String): ExamBlueprint? {
        return when (subject) {
            "Mathematics" -> Mathematics
            "ESAS" -> ESAS
            "Professional EE" -> ProfessionalEE
            else -> null
        }
    }
}
