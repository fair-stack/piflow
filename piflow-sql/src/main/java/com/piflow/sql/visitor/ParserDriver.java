package com.piflow.sql.visitor;


import cn.piflow.conf.bean.FlowBean;
import com.piflow.sql.out.SqlBaseLexer;
import com.piflow.sql.out.SqlBaseParser;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;

public class ParserDriver {
    public static void main(String[] args) {
//        String query = "SELECT LastName,FirstName FROM Persons;";

       String  query1 = "SELECT Id, Name, AVG(Score)\n" +
                "FROM Persons\n" +
                "INNER JOIN Scores\n" +
                "ON Persons.id = Scores.pId\n" +
                "GROUP BY Persons.Id, Persons.Name";

        String  query =
                "SELECT * FROM \n" +
                    "(SELECT ID,NAME,AVG(SCORE) FROM PERSONS INNER JOIN SCORES ON PERSONS.ID=PERSONS.ID GROUP BY PERSONS.ID,PERSONS.NAME)\n" +
                "WHERE ID > 1 AND NAME != 'zhuxiaojie' ";

        System.out.println(query);

        SqlBaseLexer lexer = new SqlBaseLexer(new ANTLRInputStream(query));
        SqlBaseParser parser = new SqlBaseParser(new CommonTokenStream(lexer));
        FlowBeanVisitor visitor = new FlowBeanVisitor();
        SqlBaseParser.SingleStatementContext statementContext = parser.singleStatement();
        FlowBean flowBean = visitor.visitSingleStatement(statementContext);
        String flowJson = flowBean.toJson();
        System.out.println("res="+flowJson);

        String result = SQLHttpClient.post(flowJson);
    }
}